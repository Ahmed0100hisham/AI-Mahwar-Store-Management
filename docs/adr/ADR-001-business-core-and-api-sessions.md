# ADR-001 — Shared business core and API session architecture (before API Phase 2)

| | |
|---|---|
| Status | **Decision accepted**. **Desktop v1.0.1 released/frozen** (`main` and peeled `v1.0.1`: `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b`). Shared-core adoption committed/pushed as `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99`. **API Phase 2 ready for review: 150 API tests passed (45 SQL), Desktop 168 passed**, uncommitted on `api-phase2-auth`. |
| Date | 2026-10-07 |
| Baseline | Desktop v1.0.0 (`c27b2e3`, tag `v1.0.0`), schema 1.10.0, API Phase 1 (`5cd2819`, branch `api-development`) |
| Decides | (1) how Desktop and API share business logic; (2) refresh tokens / sessions; (3) migration path, module order, DB accounts |

---

## 0. Implementation status and evidence (released Desktop 1.0.1; API adoption)

Approved execution order (replaces the parallel tracks suggested in §16): Desktop 1.0.0 frozen → API Phase 1 →
this ADR → **Desktop 1.0.1 shared-core preparation** → prove identical behaviour → freeze / release 1.0.1 → API adopts
the 1.0.1 core artifact → API Phase 2 (authentication) → REST modules → Flutter.

Implemented on branch `desktop-1.0.1-core` (from `v1.0.0`), as a behaviour-preserving internal refactor:

| Item | As implemented | Correction to the original proposal |
|---|---|---|
| Connection seam | `dao.ConnectionProvider` (`Connection getConnection()`) + `dao.ConnectionSource` (process-wide; default `ConnectionSource.DESKTOP_DEFAULT = DatabaseConnection::getConnection`). Only `BaseDao` (3 call sites) and `TransactionManager` (1) changed. | §6 proposed `open(String database)` too — **not needed**: only the backup / health DAOs open other databases, and they stay desktop-only (outside the core). The seam is a static holder (set once at startup), not constructor injection: injecting into ~25 DAOs and 14 services would have been a far larger change. |
| Audit-origin seam | `AuditLogDao(Supplier<String> origin)`; the no-argument constructor uses the unchanged static computer name. | as proposed |
| Core artifact | `almahwar-store-management-1.0.1-core.jar` (classifier `core`, `maven-jar-plugin` execution): `model`, `util`, `dao`, `service`, `config.AppConfig`, `config.DatabaseConnection`; **excluded**: `SessionManager`, `AuthServiceImpl`, `BackupRestoreServiceImpl`, `HealthCheckServiceImpl`, `SystemStatusServiceImpl`, `DatabaseBackupDao`, `BackupHistoryDao`, `DatabaseHealthDao`; no resources. `jdeps`: the only unresolved references are Apache POI (Excel export). | §5.1 assumed `AppConfig` was needed only by the default connection; it is also referenced by `util.MoneyUtil` (`formatWithCurrency`, used only by desktop controllers). It is pure JDK and stays in the core; a non-desktop host never initialises it unless it calls that method or the default provider. |
| `SecurityContext` | unchanged | — |
| Boundary tests | `CoreBoundaryTest`: no JavaFX / AWT / Swing / controller / Spring / API / `AppContext` / `AppLogging` in the core; no core class reaches a desktop-only class; connections only through the seam; every business service takes a `SecurityContext` and never `SessionManager`; the only mutable static state is `ConnectionSource.provider`. | — |
| Behaviour proof | Golden scenarios (17) recorded on v1.0.0 (`src/test/resources/golden/desktop-1.0.0-golden.txt`, 2,404 lines) are byte-identical on 1.0.1 with the `DriverManager` default and with a `javax.sql.DataSource` provider; concurrency suite results identical in pattern. | — |

**New finding (pre-existing in 1.0.0, documented, not changed):** document-number generation
(`SELECT MAX(...) WITH (UPDLOCK, HOLDLOCK)`, e.g. `SaleDao.nextNumber`) causes key-range deadlocks
(`RangeS-U` on `UQ_Sales_invoice_no`, SQL error 1205) when many new documents are created at once — reproduced with
12 simultaneous credit sales to one customer (5–9 deadlock victims per run, every victim fully rolled back, all
balances consistent). Harmless for a few desktop tills; **must be addressed (approved behaviour/schema change, e.g.
SQL `SEQUENCE` numbering or a bounded retry on 1205) before many concurrent API clients post documents** — add to
the API phase plan before Sales/POS (§13, module 8).

### API adoption after the Desktop release

API Phase 1 (`5cd2819`) consumes `com.almahwar:almahwar-store-management:jar:core:1.0.1` at runtime via local
`mvn install` from the unchanged root POM, then `mvn -f api/pom.xml verify`. No multi-module rewrite. JavaFX is
excluded; POI remains for core report-export linkage. Core/Desktop source and schema remain the official release.

`DataSourceConnectionProvider` / `CoreConnectionBinding` install Hikari behind `ConnectionSource`;
`SpringSecurityContext` maps the authenticated, live-validated principal to the existing core interface.
Core `TransactionManager` exclusively owns business transaction boundaries; no Spring transaction wraps core
service calls. `GET /api/v1/products` uses `ProductServiceImpl.search` with a bounded per-call paging DAO adapter;
HTTP contract, role grants, inactive visibility and cost protection remain compatible.

Copied PasswordHasher, Permission and RolePermissions are removed; product policy and LIKE helpers now come from
core. The bounded unknown-user throttle remains API infrastructure. Authentication/audit repositories are deferred
to preserve Phase 1 persistence and client-address behavior while Desktop AuthServiceImpl remains excluded.
The full A/B/C duplicate inventory, build instructions and verification matrix are in
[API_ARCHITECTURE.md](../API_ARCHITECTURE.md).

**API Phase 2 is implemented for review** on `api-phase2-auth`, from adoption commit
`0aa9f7b9239f6c9a060ba00c8f0309993a9deb99`. Implementation details and verification are in
[API_PHASE2_AUTH_REPORT.md](../API_PHASE2_AUTH_REPORT.md). AlMahwarDB remains schema 1.10.0, with no migration;
AlMahwarApiDB owns schema 1.0.0. No commit/tag/push is authorized for Phase 2. Flutter and Manager modules have
not started; document-number SQL 1205 remains unrelated and unfixed.

Implementation refinements to §7–§10: all hashes of a live refresh family are retained, rather than pruning to
the latest few; terminal families are deleted after 30 days in bounded batches. SQL transaction application
locks serialize user session mutations across API instances. A server-only SHA-256 credential fingerprint
supplements timestamp pwv to detect same-second Desktop resets; neither fingerprint nor password hash goes into
JWTs or session responses. API password changes advance DATETIME2(0) monotonically and use credential CAS.
Restricted users get no refresh credential; their exact allowlist includes logout. Password change commits the
business credential first, then revokes all sessions, and requires fresh login. There is no distributed transaction.
The single versioned JDBC baseline initializer replaces a migration dependency for this one API-owned schema.

## 1. Facts established from the source code

All statements below were verified in the code at `5cd2819` (paths relative to `src/main/java/com/almahwar`).

### 1.1 Dependency map of the Desktop business layers

| Dependency | `service/` (62 files, 9,392 lines) | `dao/` (29 files, 5,295 lines) | `model/` (73) | `util/` (4) |
|---|---|---|---|---|
| JavaFX / AWT / `javax.print` / Swing | **none** (grep) | none | none | none |
| Controllers / UI dialogs | none — `ArchitectureTest` forbids `service → controller`, `dao → service/controller` | none | none | none |
| Desktop session singleton | **only** `AuthServiceImpl` (field `SessionManager session`) | none | — | — |
| `SecurityContext` interface | every other `*ServiceImpl` receives it in its constructor | — | — | — |
| Static DB connection | — (except `SystemStatusServiceImpl` → `DatabaseConnection.testConnection()`) | `BaseDao` (auto-commit helpers), `TransactionManager.inTransaction`, backup / health DAOs → `config.DatabaseConnection` | — | — |
| Transaction helper | `TransactionManager.inTransaction(...)` called 35× in 14 services | DAOs take a `Connection` parameter for transactional work (20 DAOs) | — | — |
| Configuration globals | `SystemStatusServiceImpl(AppConfig)` only | `DatabaseConnection` static initializer reads `AppConfig.getInstance()` | — | — |
| Other static state | — | `AuditLogDao.MACHINE_NAME` (computer name of the process) | — | — |
| `ThreadLocal` | none | none | — | — |
| Filesystem / streams | `ReportServiceImpl` + `XlsxReportWriter` (Apache POI → `OutputStream`), `SettingsServiceImpl` (`javax.imageio`, logo bytes) | backup DAOs talk to SQL Server only (files are written by SQL Server) | — | — |
| Printing | none (printing lives in `controller/*PrintPage`) | — | — | — |
| Composition | constructor injection everywhere; wired by hand in `config/AppContext` (no controller imports) | concrete classes, no interfaces, no-arg constructors (backup/health DAOs take the database name) | | |

Proof that the services already run **outside JavaFX with a non-desktop security context**: the Desktop's own
integration tests (`src/test/java/com/almahwar/service/*IntegrationTest.java`, e.g. `SaleIntegrationTest`) build
`SaleServiceImpl`, `PurchaseServiceImpl`, `ReturnServiceImpl`… directly with `TestSecurity implements SecurityContext`.

### 1.2 `SecurityContext` (service/SecurityContext.java)

* A plain Java interface: `Optional<UserSession> getSession()` plus default methods `requireSession`,
  `currentUser`, `hasPermission`, `hasAnyPermission`, `requirePermission`, `requireAnyPermission`
  (throwing `AccessDeniedException` with Arabic messages).
* Framework-independent (imports only `model.*` and `java.util`). Its Javadoc already names "a future REST API: one
  user per HTTP request" as an intended implementation.
* `UserSession` (model) = `User` + `Set<Permission>` + login time + `passwordChangeRequired`; a session requiring a
  password change carries **no permissions** — the same rule the API enforces.
* Services call `security.…` on every method (no service caches the user in a field — grep), so a per-request
  implementation backed by Spring Security's thread-bound context works with singleton services.

### 1.3 Transaction ownership and atomicity

* **Who opens the connection:** `TransactionManager.inTransaction(work)` → `DatabaseConnection.getConnection()`
  (a new `DriverManager` connection per call — no pool), `setAutoCommit(false)`, runs `work(con)`, commits, rolls back
  on any `SQLException`/`RuntimeException`.
* **Who owns the boundary:** the **service** method (e.g. `SaleServiceImpl.post` → `inTransaction(con ->
  applyPosting(con, …))`). DAOs and the ledgers never commit; they receive the `Connection`.
* **Sale posting (one transaction):** customer row `WITH (UPDLOCK, ROWLOCK)` → `ProductDao.lockForStockChange`
  (`UPDLOCK, ROWLOCK`, `product_id` ascending — deadlock-free ordering) → stock check (`InsufficientStockException`)
  → `StockLedger.post` (movement + quantity, arithmetic cross-check) → `AccountLedger.post` (customer ledger) → cash
  transaction → quotation completion → audit rows (`POST_SALE`, `PRICE_OVERRIDE`, `CREDIT_OVERRIDE`).
* **Cashbox:** cash postings serialize on `sp_getapplock @Resource = 'AlMahwar.Cashbox'`.
* **Idempotency already in the schema:** `Sales.request_id` / `Purchases.request_id` (`UNIQUEIDENTIFIER`, unique
  filtered indexes `UX_Sales_request`, `UX_Purchases_request`) — "a retried request cannot create a second invoice".
* **Quotation conversion** is a deliberate multi-step flow (validate → `saleService.saveDraft` → link + audit), each
  step its own transaction, protected by `request_id` and the unique index `UX_Sales_quotation`; no nested
  transactions across services.
* **Isolation:** SQL Server default (READ COMMITTED, locking); no `SET TRANSACTION ISOLATION`, `SET LOCK_TIMEOUT`,
  `SET XACT_ABORT` anywhere — safe for pooled connections (HikariCP resets auto-commit/isolation it sees changed).
* **Restore:** `DatabaseBackupDao` sets the database `SINGLE_USER WITH ROLLBACK IMMEDIATE` — every other connection,
  including a future API pool, is cut during a restore.

### 1.4 Schema compatibility rule of the Desktop

`HealthCheckServiceImpl` compares `Schema_Info.schema_version` with `SettingsService.REQUIRED_SCHEMA_VERSION`
(`1.10.0`); a **newer** database is `SCHEMA_TOO_NEW`, which `DatabaseHealth.Status` marks **blocking**
(`LoginController`: `if (!h.canStart())`). ⇒ *Any* bump of the AlMahwarDB schema version stops every Desktop 1.0.0
installation until it is upgraded. Migrations are idempotent blocks appended to `database/01_create_database.sql`
(1.8.0 → 1.9.0 → 1.10.0), each moving the version forward only.

### 1.5 Desktop sessions

Desktop permissions are fixed at login (`SessionManager.start`) and never re-checked against the database; the
session ends at logout or after `app.session.timeout-minutes` (30) of inactivity (`Navigator` idle timer). Users are
never deleted (no `DELETE FROM dbo.Users`), only disabled.

---

## 2. Classification of the existing code

| Cat. | Meaning | Classes |
|---|---|---|
| **A** | Pure domain rules (no I/O) | `CredentialPolicy`, `CostingPolicy`, `FinanceRules`, `PaymentRules`, `PartyRules`, `BackupPolicy`, `Validation`, `RolePermissions`, `model.*` (incl. `Permission`, `CreditStatus`, `UserSession`), `util.MoneyUtil`, `util.QuantityUtil`, `util.PasswordHasher`, `util.PhoneNumbers`, business exceptions (`ValidationException`, `AccessDeniedException`, `InsufficientStockException`, `CreditLimitExceededException`, `AuthenticationException`) |
| **B** | Application services (rules + orchestration, injectable) | `SaleServiceImpl`, `PurchaseServiceImpl`, `ReturnServiceImpl`, `QuotationServiceImpl`, `PaymentServiceImpl`, `ExpenseServiceImpl`, `CashboxServiceImpl`, `InventoryServiceImpl`, `ProductServiceImpl`, `CatalogServiceImpl`, `CustomerServiceImpl`, `SupplierServiceImpl`, `UserServiceImpl`, `DashboardServiceImpl`, `ReportServiceImpl` (+ `ReportTables`), `SettingsServiceImpl`, **`StockLedger`**, **`AccountLedger`** (the posting invariants) |
| **C** | Persistence | all `dao/*Dao`, `BaseDao`, `RowMapper`, `TransactionManager`, `DataAccessException` — seam: `config.DatabaseConnection` |
| **D** | Desktop/UI only | `controller/**` (incl. `*PrintPage`, `Navigator`, dialogs, `Icon*`), FXML/CSS, `MainApp`, `Launcher`, `SessionManager`, `AuthServiceImpl` (session start part), `config.AppContext` |
| **E** | Infrastructure | `config.AppConfig`, `config.DatabaseConnection`, `config.AppLogging`, `BackupRestoreServiceImpl` + `DatabaseBackupDao` + `BackupHistoryDao` (server admin), `HealthCheckServiceImpl` + `DatabaseHealthDao`, `SystemStatusServiceImpl`, `XlsxReportWriter`, `LoginAttemptTracker` (process memory) |
| **F** | API only | everything under `api/` (HTTP, JWT, DTOs, OpenAPI, `ApiError`, Spring config) |

The things worth sharing — **A + B + C** — already form a clean, JavaFX-free layer. What prevents reuse in a server is
only (1) the static connection source in category C and (2) two small static/process assumptions
(`AuditLogDao.MACHINE_NAME`, `AuthServiceImpl`'s `SessionManager`).

## 3. Duplication risk today

The API Phase 1 already duplicates — by porting, guarded by parity tests — `PasswordHasher`, `Permission`,
`RolePermissions`, `LoginAttemptTracker`, the lockout SQL and the product list query. That is tolerable for small,
stable primitives. Porting **category B** would duplicate ~3,500 lines of the most delicate code
(`SaleServiceImpl` 711, `ReturnServiceImpl` 504, `QuotationServiceImpl` 507, `PurchaseServiceImpl` 445,
`PaymentServiceImpl` 240, ledgers) including lock order, credit-limit override rules, price-override auditing, costing
and idempotency. Divergence there corrupts stock and money — unacceptable.

---

## 4. Options

### Option A — separate implementations (Desktop services + API services)
* + No Desktop change ever. + Each side free to evolve.
* − Every rule written twice (and re-tested twice); silent divergence in stock/ledger/credit logic; two lock orders
  that must stay identical or desktop-vs-API deadlocks appear; security reviews double; parity tests cannot cover
  behaviour as large as posting.
* Verdict: acceptable only for thin read projections; **rejected for mutations**.

### Option B — shared business-core artifact
* The existing `model`, `util`, `dao`, `service` packages (already JavaFX-free and enforced by `ArchitectureTest`)
  become the business core used by both Desktop and API.
* Needed seams (small): an injectable connection source behind `BaseDao`/`TransactionManager`; an injectable audit
  origin instead of the static `MACHINE_NAME`; a core artifact the API can depend on.
* + One implementation of every invariant; the Desktop's 357 tests (incl. 13 SQL Server integration suites) keep
  guarding it; the API inherits rules, lock order, idempotency for free.
* − Requires a behaviour-preserving Desktop maintenance release; the API must respect the core's transaction model.
* Verdict: **feasible with low code churn** — the layering already exists.

### Option C — API as the only business server (Desktop becomes an HTTP client)
* + Single authorization boundary, single deployment of rules, easier multi-device consistency.
* − Rewrites the data access of a mature, accepted desktop (46 controllers, 13,962 lines call services directly);
  the shop's tills stop when the API host is down (today they need only SQL Server on the LAN); backup/restore
  (`SINGLE_USER`, `master`, `BACKUP DATABASE`) would need sysadmin-grade rights inside an Internet-facing service;
  printing/report export would need new transport; huge migration risk for no current business need.
* Verdict: **rejected now**; remains a possible long-term direction once the core is shared (then it becomes a
  deployment choice, not a rewrite).

### Option D — ports & adapters with a new domain layer
* Formal domain model + repository ports + adapters for Desktop and Spring.
* − Means re-writing services against new ports and new persistence adapters: effectively a second implementation
  plus migration of the first. The current code already *is* "service + DAO receiving a Connection"; the two seams of
  Option B give the useful part of ports/adapters (swappable connection source and security context) at a fraction
  of the cost.
* Verdict: **rejected** as architecture for its own sake.

---

## 5. Decision — business core

### RECOMMENDED ARCHITECTURE: **Option B, minimal-change variant — "Shared Business Core from the existing Desktop layers"**

The Desktop's `model`, `util`, `dao` and `service` packages (+ `config.AppConfig`/`config.DatabaseConnection` as the
default connection source) are the single business core. They are **not moved**: the existing Desktop build publishes
them additionally as a separate artifact (`almahwar-store-management` with classifier `core`). The API depends on
that artifact and plugs in two adapters:

```
                    ┌──────────────── business core (existing packages, one implementation) ───────────────┐
JavaFX controllers ─┤  service.*  (rules, permissions via SecurityContext, transaction boundaries)          │
                    │  ledgers: StockLedger, AccountLedger                                                  │
Spring controllers ─┤  dao.*      (SQL, locks, idempotency)  ──►  ConnectionProvider (seam)                 │
   (API adapters)   └───────────────────────────────────────────────┬──────────────────────────────────────┘
                                                                    │
            Desktop: DriverManager provider (today's behaviour)     │   API: HikariCP DataSource provider
                                                                    ▼
                                                              SQL Server (AlMahwarDB)
```

**Why:** the business layers are already framework-free (§1.1), injectable (§1.1), security-abstracted (§1.2) and
transaction-explicit (§1.3); the only obstacles are static connection/config wiring and one static audit field.
Fixing two seams is far smaller and safer than duplicating (A), rewriting the Desktop (C) or adding layers (D).

### 5.1 Module / package boundaries

| Unit | Contents | May depend on |
|---|---|---|
| core (published from the Desktop build, classifier `core`) | `com.almahwar.model`, `util`, `dao`, `service` (except `SessionManager`, `AuthServiceImpl` stays but is not used by the API), `config.AppConfig`, `config.DatabaseConnection` | JDK, mssql-jdbc, Apache POI |
| desktop (unchanged location) | `controller`, `MainApp`, `Launcher`, `config.AppContext`, `config.AppLogging`, FXML/CSS | core, JavaFX |
| api (`api/`) | HTTP, security (JWT, `SecurityContext` adapter), DTOs, error model, API-owned session store | core (`exclusions *` for JavaFX), Spring |

### 5.2 Dependency direction (enforced by tests on both sides)

`api → core`, `desktop → core`; **never** `core → desktop UI`, `core → Spring`, `core → api`. The Desktop
`ArchitectureTest` gains rules "core packages import no `config.AppContext`/`AppLogging`/`controller`/`javafx`";
the API `ApiArchitectureTest` gains "no API class re-implements a core service" (only adapters/DTO mapping).

### 5.3 Rules for the API when calling the core

* The **core owns transaction boundaries** (its `TransactionManager`). API controllers/application services call a
  core service method and map the result; they do **not** wrap core calls in Spring `@Transactional` (that would open a
  second connection and give a false sense of atomicity). Spring transactions are used only for API-owned storage.
* Authorization stays in the core (`security.require…`) — the API adapter supplies the `SecurityContext`; API URL
  rules remain as defence in depth. Business exceptions map to `ApiError` (`ValidationException` → 400/422 with field,
  `AccessDeniedException` → 403, `InsufficientStockException` / `CreditLimitExceededException` → 409 with details).
* Mutations send the existing `request_id` (UUID from the client) for idempotent retries.

### 5.4 Incremental migration path

| Step | What | Desktop impact | Gate |
|---|---|---|---|
| 0 | This ADR approved | none | user approval |
| 1 | **Desktop 1.0.1** (behaviour-preserving, see §6) on a branch from `v1.0.0`: `ConnectionProvider` seam, audit-origin seam, `core` classifier JAR, extra architecture tests | none visible; same schema 1.10.0 | full Desktop suite (357) + SQL Server integration suites + E2E smoke + golden tests (§12) identical to 1.0.0 |
| 2 | API depends on `core` 1.0.1: `SpringSecurityContext implements SecurityContext`, `DataSourceConnectionProvider` (Hikari) | none | API parity tests now use the real core classes; ported `Permission`/`RolePermissions`/`PasswordHasher` copies are deleted in favour of core |
| 3 | Read endpoints via core services (products, catalog, inventory, customers, suppliers) | none | API ↔ Desktop result parity tests on a temp DB |
| 4 | Mutations via core services (quotations → sales → purchases → payments/expenses → returns) | none | concurrency tests desktop-vs-API on the same rows (§12) |
| 5 (optional, later) | Physical `core/` Maven module + aggregator (repository restructure) | build layout only | only if the classifier approach becomes limiting |

### 5.5 Risks and mitigations

| Risk | Mitigation |
|---|---|
| A seam change alters Desktop behaviour | Default implementations are the current code moved behind an interface; golden tests + full suites + E2E; diff review limited to `BaseDao`, `TransactionManager`, `AuditLogDao`, pom |
| Core loads Desktop config in the API process | API sets the provider before first use; `DatabaseConnection`/`AppConfig` are then never initialised; test asserts no `AppConfig` load in the API |
| JavaFX leaks into the API through Maven | `exclusions *` on the core dependency + `ApiArchitectureTest` asserting no `javafx` on the API runtime classpath |
| Restore cuts API connections | API maps connection errors to 503; readiness turns NOT_READY; documented operator step (stop API during restore) |
| One more connection client (API pool) on SQL Server | pool size small (10); lock order and app-locks are shared code, so desktop and API serialize correctly |

### 5.6 Impact summary

* **Desktop:** one maintenance release (1.0.1) with internal seams only; no UI, schema, rule or permission change.
* **API:** stops porting business logic; becomes HTTP + security + DTO adapters over the core; its ported copies are
  removed after Step 2.
* **SQL Server:** no schema change for the core; one more client (API pool) using the same locks.
* **Flutter (future):** talks only to the API; gets exactly the Desktop's rules, permissions and idempotency.

---

## 6. Desktop maintenance release

**Recommendation: B — Desktop 1.0.1, strictly behaviour-preserving.** (A is impossible: the static
`DatabaseConnection` cannot be replaced without touching `BaseDao`/`TransactionManager`. C is unnecessary: no
redesign is needed.)

Allowed in 1.0.1 (internal refactor only):
1. `dao.ConnectionProvider` interface (`Connection open()`, `Connection open(String database)`); `BaseDao` and
   `TransactionManager` obtain connections through a provider registered once at startup, defaulting to the current
   `DatabaseConnection` (same URL, same properties, same `DriverManager`). No change to SQL, locks or commit/rollback.
2. `AuditLogDao` machine name from a supplier, defaulting to the current computer-name logic.
3. Build: an additional `core` classifier JAR (model/util/dao/service + `AppConfig`/`DatabaseConnection`); the
   application JAR, `Main-Class` (`com.almahwar.Launcher`) and its content stay identical except the two seams.
4. New architecture tests for the core boundary; golden tests (§12).

Not allowed in 1.0.1: UI, FXML/CSS, permissions, rules, SQL text, schema/migrations, configuration keys, logging
behaviour visible to users, dependency upgrades. Version bump `1.0.0 → 1.0.1`; schema stays **1.10.0**.

---

## 7. Sessions and refresh tokens

### 7.1 Threat model

| Threat | Consequence | Control |
|---|---|---|
| Access token stolen (device malware, logs, proxy) | act as user ≤ 15 min | short TTL; HTTPS; never logged; per-request DB state check |
| Refresh token stolen | long-lived account access | opaque, stored hashed, rotation + reuse detection (family revoked), absolute and idle expiry, logout/logout-all, secure device storage |
| Database read (backup leak, SQL injection elsewhere) | token theft from DB | only SHA-256 digests stored — useless without the raw value |
| Revoked token resurrected by a database **restore** | logout / stolen-device revocation undone | sessions stored **outside** AlMahwarDB (§7.4); restore of AlMahwarDB cannot bring them back |
| Signing key leak | forge any access token | key only in server secret store; rotation procedure; tokens short-lived |
| Brute force on refresh endpoint | guess a token | 256-bit random values; rate limit at proxy |
| Admin disables / resets user while sessions exist | continued access | every refresh re-checks `Users.is_active`, role permissions and password version (`pwv`) |

### 7.2 Why SHA-256 is right for refresh tokens

A refresh token is 32 random bytes from `SecureRandom` (256 bits of entropy). Slow password hashes (PBKDF2, bcrypt)
exist to resist guessing **low-entropy** human passwords; a 256-bit random value cannot be guessed, so a single
SHA-256 digest is sufficient, standard and allows an indexed exact-match lookup (`WHERE token_hash = ?`).
No salt is needed (inputs are unique and random). Comparison happens by index lookup of the digest, so the raw token
is never compared in application memory against stored values.

### RECOMMENDED SESSION ARCHITECTURE: **"Short JWT access token + rotating opaque refresh token, stored hashed per device session in an API-owned database (`AlMahwarApiDB`), validated against live `dbo.Users` state on every refresh"**

### 7.3 Lifecycle

| Item | Decision |
|---|---|
| Access token | JWT, 15 minutes (unchanged), `sub`, `iss`, `aud`, `iat`, `exp`, `jti`, `pwv`, **+ `sid`** (session id, for logout) |
| Refresh token | opaque, 32 bytes `SecureRandom`, base64url (43 chars), prefixed `amr_` for secret scanners; only `SHA-256` stored |
| Session (family) | one row per device login: created on login, holds the user, password version, device label, expiry |
| Rotation | every successful refresh issues a new refresh token and marks the old one `used`; one-time use |
| Reuse detection | presenting an already-used (rotated) token ⇒ revoke the whole session (all its tokens) + audit `API_REFRESH_REUSE` (grace window: none; client must serialise refreshes) |
| Lifetimes | refresh token idle: 8 h (one shop shift); session absolute: 7 days, then full login |
| Validation on refresh | token digest exists, not used, not revoked, not expired; session active; `Users.is_active = 1`; role has permissions; session `pwv` == current `password_changed_at` version; `must_change_password` honoured (§9) |
| Transport | refresh token only in JSON body of `POST /auth/refresh` / `logout`; never in URL, header logs or query |

### 7.4 Where to store sessions — why not AlMahwarDB 1.11.0

The prompt's default (table in AlMahwarDB, schema 1.11.0) was evaluated and **rejected**:
1. **Desktop lock-out:** `SCHEMA_TOO_NEW` is blocking (§1.4): migrating AlMahwarDB to 1.11.0 stops every Desktop
   1.0.0 PC until all are upgraded at the same moment — an outage risk at a shop, for a table the Desktop never uses.
2. **Restore resurrection:** the Desktop restores AlMahwarDB from backups; session rows would roll back with it,
   re-enabling refresh tokens that had been rotated, logged out or revoked (e.g. a stolen phone).
3. **Least privilege:** keeping API-owned, high-churn data out of the business database lets the API login be
   read-mostly on AlMahwarDB.

**Decision:** a separate database **`AlMahwarApiDB`** on the same SQL Server instance, owned by the API, with its own
version table and script (`database/api/01_create_api_database.sql`, API session schema **1.0.0**). AlMahwarDB stays
**1.10.0** — no Desktop release is needed for Phase 2. Losing or restoring `AlMahwarApiDB` only forces users to log in
again (fails safe). No cross-database transaction is needed: security does not depend on atomic revocation, because
every refresh re-reads `dbo.Users` (active, role, password version). A foreign key to `dbo.Users` is not possible
across databases and not needed (users are never deleted, §1.5; a missing user fails the refresh).

Fallback if a second database is not acceptable operationally: a table in AlMahwarDB **only** together with a Desktop
release that accepts 1.11.0 (coordinated upgrade of all PCs) and a post-restore "revoke all API sessions" step.

### 7.5 Proposed tables (`AlMahwarApiDB`, design only — not created)

```sql
CREATE TABLE dbo.Api_Schema_Info (
    id INT NOT NULL CONSTRAINT PK_Api_Schema_Info PRIMARY KEY CONSTRAINT CK_Api_Schema_Info_id CHECK (id = 1),
    schema_version VARCHAR(20) NOT NULL,            -- '1.0.0'
    updated_at DATETIME2(0) NOT NULL CONSTRAINT DF_Api_Schema_Info_updated_at DEFAULT (SYSUTCDATETIME())
);

CREATE TABLE dbo.Api_Sessions (                      -- one per device login ("token family")
    session_id        UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_Api_Sessions PRIMARY KEY NONCLUSTERED,
    session_no        BIGINT IDENTITY(1,1) NOT NULL, -- clustered, insert-friendly
    user_id           INT              NOT NULL,     -- AlMahwarDB.dbo.Users.user_id (no cross-DB FK)
    password_version  VARCHAR(20)      NOT NULL,     -- pwv at login; mismatch ⇒ session dead
    device_label      NVARCHAR(100)    NULL,         -- client-supplied, e.g. "Samsung A54 — كاشير 2"; user-visible
    created_at        DATETIME2(0)     NOT NULL CONSTRAINT DF_Api_Sessions_created_at DEFAULT (SYSUTCDATETIME()),
    last_refresh_at   DATETIME2(0)     NULL,
    expires_at        DATETIME2(0)     NOT NULL,     -- absolute end (created + 7 days)
    revoked_at        DATETIME2(0)     NULL,
    revoked_reason    VARCHAR(30)      NULL,
    CONSTRAINT CK_Api_Sessions_reason CHECK (revoked_reason IN
        ('LOGOUT','LOGOUT_ALL','REUSE_DETECTED','PASSWORD_CHANGED','USER_DISABLED','ADMIN_REVOKED','EXPIRED'))
);
CREATE UNIQUE CLUSTERED INDEX CX_Api_Sessions_no ON dbo.Api_Sessions (session_no);
CREATE INDEX IX_Api_Sessions_user_active ON dbo.Api_Sessions (user_id) INCLUDE (revoked_at, expires_at);

CREATE TABLE dbo.Api_Refresh_Tokens (
    token_id     BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Api_Refresh_Tokens PRIMARY KEY,
    session_id   UNIQUEIDENTIFIER NOT NULL
        CONSTRAINT FK_Api_Refresh_Tokens_Sessions REFERENCES dbo.Api_Sessions (session_id) ON DELETE CASCADE,
    token_hash   BINARY(32)   NOT NULL,              -- SHA-256 of the raw token; raw value never stored
    issued_at    DATETIME2(0) NOT NULL CONSTRAINT DF_Api_Refresh_Tokens_issued_at DEFAULT (SYSUTCDATETIME()),
    expires_at   DATETIME2(0) NOT NULL,              -- issued + idle lifetime, capped by session expiry
    used_at      DATETIME2(0) NULL,                  -- set on rotation; a second use = reuse
    CONSTRAINT UQ_Api_Refresh_Tokens_hash UNIQUE (token_hash)
);
CREATE INDEX IX_Api_Refresh_Tokens_session ON dbo.Api_Refresh_Tokens (session_id);
```

* **Timestamps:** UTC (`SYSUTCDATETIME()`) in the API database — it is new, API-only, and compared with server-side
  expiry only. (AlMahwarDB keeps its local `SYSDATETIME()` convention; `pwv` is compared by equality, so the two
  conventions never mix.)
* **Privacy:** no IP addresses stored in session rows (IP is in the audit log already, as `API/<address>`);
  `device_label` is optional, client-supplied and shown to the user in "my sessions".
* **Cleanup:** a daily job inside the API deletes sessions whose `expires_at` / `revoked_at` is older than 30 days
  (cascade removes tokens). Bounded size: tokens per session are pruned on rotation (keep the latest few for reuse
  detection).
* **Audit:** security events go to AlMahwarDB `Audit_Log` with existing style action codes: `LOGIN`, `LOGOUT`,
  new `API_LOGOUT_ALL`, `API_REFRESH_REUSE`, `API_SESSION_REVOKED` (the `action` column is free text — no schema
  change).

### 7.6 Logout and revocation semantics

| Event | Access tokens | Refresh tokens / sessions | How |
|---|---|---|---|
| Logout (this device) `POST /auth/logout` | this session's access token rejected at once (`sid` revoked check) | this session revoked (`LOGOUT`) | body: refresh token or the access token's `sid` |
| Logout all `POST /auth/logout-all` | all of the user's sessions' tokens rejected | all sessions of the user revoked (`LOGOUT_ALL`) | requires a valid access token |
| Change own password | all earlier tokens dead (`pwv` changes) | all sessions dead on next refresh (`pwv` mismatch) + revoked eagerly (`PASSWORD_CHANGED`) | then the client logs in with the new password |
| Admin resets password (Desktop) | dead at next request (`pwv`) | dead at next refresh (`pwv`); marked revoked lazily | no API involvement needed |
| Admin disables user (Desktop) | rejected at next request (`is_active`) | refused at next refresh; revoked lazily (`USER_DISABLED`) | |
| Admin changes role (Desktop) | new permissions at next request | session continues with new permissions | no revocation needed |
| Account locked (wrong passwords) | existing sessions continue (as Desktop) | continue | lock only stops new logins |
| Refresh token reuse | session's access tokens rejected (`sid` revoked) | whole session revoked (`REUSE_DETECTED`) | audit |

**Access-token revocation decision:** no `jti` blacklist. A per-request check already reloads the user
(`is_active`, role, `pwv`); adding a session check (`sid` → `Api_Sessions.revoked_at`) gives immediate logout at the
cost of one indexed lookup in the API database per request (cacheable for a few seconds). A per-token blacklist would
add a third store with no benefit over `sid` + 15-minute expiry.

### 7.7 JWT signing algorithm

| | HS256 (current) | RS256 / ES256 |
|---|---|---|
| Fits one API process | yes, simplest | yes |
| Several API instances | all need the same secret (shared secret distribution) | all need the private key to *sign*, or one auth service signs |
| Other verifiers (reports service, gateway) | must hold the secret = can also mint tokens | verify with public key only (JWKS) |
| Key rotation | restart with new secret ⇒ all users re-login (refresh tokens survive and re-issue) | `kid` header, overlap of old/new keys |

**Recommendation:** keep **HS256** for the current single-server deployment (strong 384-bit secret from a secret
store, already enforced). Move to **ES256 with `kid`** when (a) a second service must verify tokens, or (b) more than
one API instance is deployed with zero-downtime key rotation. The refresh-token design is independent of this choice.

---

## 8. Phase 2 auth endpoints (design)

| Endpoint | Auth | Request | Response | Notes |
|---|---|---|---|---|
| `POST /api/v1/auth/login` | public | `{username, password, deviceLabel?}` | `{accessToken, expiresIn, refreshToken, refreshExpiresIn, sessionId, mustChangePassword, user}` | creates session; `Cache-Control: no-store` |
| `POST /api/v1/auth/refresh` | public (refresh token is the credential) | `{refreshToken}` | same shape as login, new pair | rotation; 401 `SESSION_REVOKED` on any failure; generic message |
| `POST /api/v1/auth/logout` | access token | `{refreshToken?}` | 204 | revokes the current session (`sid`) |
| `POST /api/v1/auth/logout-all` | access token | — | 204 | revokes all sessions of the user |
| `POST /api/v1/auth/change-password` | access token (allowed while must-change) | `{currentPassword, newPassword, confirmPassword}` | 204 | core `CredentialPolicy` + the Desktop's change-password rules; revokes all sessions; audit `PASSWORD_CHANGED` |
| `GET /api/v1/auth/me` | access token | — | user, role, permissions, `mustChangePassword` | unchanged |
| `GET /api/v1/auth/sessions` (optional) | access token | — | own sessions (device label, created, last refresh) | lets a user see/revoke devices |

Refresh tokens appear only in JSON bodies; request/response DTOs redact them in `toString()`; logging never prints
bodies. Errors stay in the `ApiError` format.

## 9. must_change_password flow

1. Login with valid credentials ⇒ `mustChangePassword: true`, an access token (session marked restricted) and **no
   refresh token** (a restricted session must not be extendable).
2. With that token only `GET /auth/me` and `POST /auth/change-password` are reachable (existing
   `PasswordChangeRequiredFilter`; core sessions also carry no permissions).
3. `change-password` validates with the Desktop `CredentialPolicy` (via core), sets the new hash with
   `must_change_password = 0` and a new `password_changed_at` (Desktop `UserDao.setPassword` semantics), audits, and
   revokes every session of the user.
4. Response 204; the client must log in again with the new password, receiving a normal session + refresh token.
5. A restricted access token expires in ≤ 15 min; no other endpoint is ever reachable before the change.

## 10. Flutter token storage (future requirement — no Flutter work now)

* Refresh token and session id: **platform secure storage only** — Android Keystore-backed encrypted storage, iOS
  Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), via a maintained abstraction such as
  `flutter_secure_storage`. Never `SharedPreferences`, plain files or plaintext SQLite.
* Access token: memory only (re-obtained via refresh on app start).
* Serialise refresh calls (single-flight) to avoid triggering reuse detection; on 401 `SESSION_REVOKED` wipe
  secure storage and return to login. Certificate pinning to be considered in API Phase 10.

## 11. Cross-client behaviour (Desktop and API sessions of the same user)

| Admin action (on Desktop) | Desktop session of that user | API/mobile session of that user |
|---|---|---|
| Disable account | continues until logout or 30-min idle timeout (Desktop never re-checks) | rejected on next request (401 `SESSION_REVOKED`) |
| Change role | keeps old permissions until next Desktop login | new permissions on next request |
| Reset password | continues until logout/timeout; next login forces change | rejected on next request (`pwv`); next login forces change |

The API is stricter than the Desktop by design. Bringing the Desktop to the same strictness (periodic session
re-validation) is a Desktop behaviour change and is **out of scope** for 1.0.1; noted as a candidate for a later
Desktop 1.1.0.

---

## 12. SQL Server accounts

| Login | Used by | Rights |
|---|---|---|
| `almahwar_desktop` (operational) | Desktop PCs | current Desktop needs on AlMahwarDB (read/write business tables) — as documented in README "Production deployment" |
| `almahwar_backup` / DBA (administrative) | the administrator doing backup/restore from the Desktop | `BACKUP DATABASE`, restore rights (`dbcreator`/sysadmin-level), `master` access — **never** used by the API |
| `almahwar_api` (least privilege) | API service only | see below; no `master`, no backup/restore, no DDL |

**Phase 2 API rights:** on AlMahwarDB — `SELECT` on `Schema_Info, Roles, Users, Products, Categories, Units,
Brands`; `UPDATE (failed_login_attempts, locked_until, last_login_at, password_hash, must_change_password,
password_changed_at, updated_at)` on `Users` (change-password adds the last two); `INSERT` on `Audit_Log`.
On AlMahwarApiDB — `SELECT, INSERT, UPDATE, DELETE` on `Api_Sessions`, `Api_Refresh_Tokens`; `SELECT` on
`Api_Schema_Info`. When mutations go through the core (Step 4), the API login receives exactly the table rights of
the Desktop operational login for those modules (plus `EXECUTE` on `sp_getapplock` via public), never admin rights.

## 13. REST module order (derived from service dependencies)

| # | Module | Core service(s) and dependencies | Writes? | Risk |
|---|---|---|---|---|
| 1 | Auth completion (refresh, logout, change password) | `CredentialPolicy`, `UserDao.setPassword` semantics; API session DB | auth only | **Medium** (security-critical, isolated) |
| 2 | Master data reads: categories, brands, units | `CatalogServiceImpl` (3 DAOs) | no | **Low** |
| 3 | Products & inventory reads (detail, barcode scan, stock, movements) | `ProductServiceImpl`, `InventoryServiceImpl` (+ `StockMovementDao`) | no | **Low** |
| 4 | Customers / suppliers reads, balances, statements | `CustomerServiceImpl`, `SupplierServiceImpl`, `AccountLedger` (read) | no | **Low–Medium** (balance visibility permissions) |
| 5 | Dashboard / reports (read, paged) | `DashboardServiceImpl`, `ReportServiceImpl` | no | **Medium** (heavy queries; cost/profit permissions) |
| 6 | Customers / suppliers / products edits | same services | yes, single-entity | **Medium** |
| 7 | Quotations (draft/send/accept) | `QuotationServiceImpl` (no stock/ledger until conversion) | yes | **Medium** |
| 8 | Sales / POS posting, quotation conversion | `SaleServiceImpl` → `StockLedger`, `AccountLedger`, cash app-lock, `request_id` | yes | **High** — only after core sharing (Step 2) |
| 9 | Purchases posting | `PurchaseServiceImpl` → `StockLedger`, `AccountLedger`, `CostingPolicy`, cash | yes | **High** |
| 10 | Payments, expenses, cashbox | `PaymentServiceImpl`, `ExpenseServiceImpl`, `CashboxServiceImpl` | yes | **High** (money) |
| 11 | Returns | `ReturnServiceImpl` → Sale/Purchase DAOs + both ledgers + cash | yes | **High** (depends on 8–10) |
| — | Users admin, settings, backup/restore | `UserServiceImpl`, `SettingsServiceImpl`, `BackupRestoreServiceImpl` | admin | **Not planned for mobile** (backup/restore stays Desktop-only) |

Rule: modules 2–7 may start on the current API (reads) but should switch to core services as soon as Step 2 lands;
modules 8–11 start **only** on the shared core.

## 14. Testing strategy (shared core)

* **Golden behaviour tests (before the 1.0.1 refactor):** on a temporary database, run fixed scenarios through the
  1.0.0 services (sale with discount and credit override, purchase with costing, sale/purchase returns, payments,
  quotation conversion, stock adjustment) and record every resulting row (Sales, Sale_Items, Stock_Movements,
  Products.quantity, Account_Ledger, Cash_Transactions, Audit_Log minus timestamps/ids). Re-run on 1.0.1: results must
  be **identical**.
* Existing Desktop suites: 357 tests + all 13 SQL Server integration suites + E2E smoke harnesses on the 1.0.1 JAR.
* Transaction tests: failure injected mid-posting ⇒ nothing persisted (rollback), on both providers (DriverManager,
  Hikari).
* Concurrency tests: Desktop-core and API-core posting sales on the same products/customer/cashbox in parallel ⇒ no
  deadlock, no negative stock, balances consistent.
* Permission parity: for every role and every core service method exposed by the API, API outcome (200/403) equals
  the core's `requirePermission` outcome.
* API integration tests on a temporary database (as in Phase 1), never on AlMahwarDB.

## 15. Rollback strategy

* Desktop 1.0.1: if any regression appears, reinstall the preserved 1.0.0 JAR (`dist/release-1.0.0/`, SHA-256
  recorded); schema unchanged (1.10.0), so no database rollback is needed.
* API using the core: the API is a separate deployment; roll back the API JAR independently; Desktop unaffected.
* `AlMahwarApiDB`: dropping/re-creating it only logs every mobile user out; it never affects AlMahwarDB.
* Every step lands on its own branch/commit; `v1.0.0` is never modified.

## 16. Exact recommended next step

> Superseded by the approved execution order in §0: the Desktop 1.0.1 core preparation comes first; API Phase 2
> starts only after 1.0.1 is released and the API has adopted the core artifact. The original text follows.

Approve (or amend) this ADR. Then, as two **separate, explicitly approved** tracks:
1. **API Phase 2 (auth completion)** — can start immediately: create `AlMahwarApiDB` script (API session schema 1.0.0),
   refresh/logout/logout-all/change-password per §7–§9. It needs no Desktop change; `change-password` uses the ported
   `CredentialPolicy` with a parity test until the core artifact exists.
2. **Desktop 1.0.1 (core seams)** — branch from `v1.0.0`; write golden tests first on 1.0.0; then the three seams of §6;
   release only after identical results. Required before any sales/purchase/finance/returns endpoint.

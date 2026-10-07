# Al Mahwar Store Management System — REST API Architecture

Status: **API Phase 3 Manager reads implemented; verification recorded in the [Phase 3 report](API_PHASE3_MANAGER_READ_REPORT.md)** · branch `api-phase3-manager-read` · Spring Boot 4.1.1 · Java 17
Frozen baseline: released Desktop **v1.0.1** (tag `v1.0.1`, commit `c0234e4`), database schema **1.10.0**.

---

## 1. Purpose

The REST API is the only way mobile clients (the future Flutter app) reach the business data. It is the
**security and business-logic boundary**: every rule the desktop enforces (authentication, lockout, permissions,
validation, transactions, stock / ledger invariants) is enforced again here, on the server, for every request.

Phase 1 delivers the foundation only — configuration, database connectivity and compatibility check, health,
error model, validation, security (login, tokens, authorization), pagination, OpenAPI, tests — and one read-only
proof-of-concept endpoint (`GET /api/v1/products`). It does **not** expose sales, purchases, payments or any mutation.

Phase 2 adds authenticated device sessions and rotating refresh tokens. Phase 3 adds 19 GET-only Manager
routes for dashboard, sales analytics, expenses/cash book, inventory, product history and party accounts.
The complete endpoint, permission, financial and verification contract is in the
[Phase 3 Manager read report](API_PHASE3_MANAGER_READ_REPORT.md). Business mutations and Flutter remain outside scope.

## 2. Architecture

```
Flutter (future Al Mahwar Manager) -- HTTPS --> Spring Boot REST API
                                                  |
                                      request SecurityContext adapter
                                                  |
Desktop JavaFX ---------------------------> Shared Business Core 1.0.1
                                                  |
                                              DAO / JDBC
                                                  |
                                         AlMahwarDB (1.10.0)
```

The API is an independent Maven project in `api/`; the root Desktop project, source, resources, configuration,
SQL and build definition remain identical to released v1.0.1. No reactor or module rewrite is involved.
The dependency direction is API → core → JDBC; core has no Spring, API, Flutter or UI dependency.

**Official artifact:** `com.almahwar:almahwar-store-management:jar:core:1.0.1`.
Run from the repository root (Java 17 and Maven):

```powershell
mvn install
mvn -f api/pom.xml verify
```

`mvn install` builds/tests the unchanged Desktop and installs its POM plus the attached `core` classifier into
Maven's local repository. `mvn install -DskipTests` is available for a subsequent rebuild. No generated JAR is
checked in and no absolute path is embedded. Fresh builds must install the core before building `api/`.
The classifier shares the Desktop POM: API explicitly excludes `javafx-controls` and `javafx-fxml`, including their
UI transitives. SQL Server JDBC remains; Apache POI remains transitively available for core report export.
Spring Boot dependency management still applies; no explicit dependency or Spring Boot upgrade is part of adoption.

**Connection adapter:** `core.DataSourceConnectionProvider` obtains a fresh connection directly from the API
Hikari `DataSource`. `CoreConnectionBinding` installs it in the released `ConnectionSource` before product queries
can execute. Connection acquisition failures retain the Phase 1 safe 503 response. The core closes connections;
Hikari returns them to the pool and resets auto-commit. The Desktop default remains
`DatabaseConnection::getConnection`. The provider is process-wide: run one API application per JVM; it is never
changed per request. Closing an owning context restores its previous provider, including nested test contexts.

**Security adapter:** `core.SpringSecurityContext` reads the authenticated Spring Security `ApiUser` on each call.
`UserPrincipalLoader` has already loaded live user state and rejected disabled/deleted users and stale `pwv` tokens.
It maps only identity/role fields into a core `UserSession`, intersects principal permissions with the released role
matrix, and honors `must_change_password` by granting no permissions. No password/hash or invented login session is
mapped. Spring endpoint checks remain defense in depth; the released core service is authoritative.

**Products POC:** `ProductQueryService` calls the real `ProductServiceImpl.search`. A per-call `PagedProductDao`
receives the core-authorized filter and delegates bounded count/page SQL to `ProductRepository`, which uses shared
`BaseDao` JDBC helpers, binding and LIKE escaping through `ConnectionSource`. This paging adapter remains API-owned
because the immutable core DAO exposes an unpaged list only. The core controls permission checks, inactive-product
visibility and cost hiding; cost is also omitted in SQL and DTOs for unauthorized users. Public HTTP paths, DTOs,
limits, sort allowlist and errors are unchanged. No business mutation is exposed.

### Duplicate inventory and adoption decisions

| Duplicate / overlap with released core | Class | Decision and reason |
|---|---|---|
| PBKDF2 password hashing | A | Deleted API `security.PasswordHasher`; runtime imports shared `util.PasswordHasher` (600,000 iterations). |
| Permission enum and role grants | A | Deleted API `security.Permission` / `security.RolePermissions`; all callers use the released core classes. |
| Product permission, inactive and cost rules | A | Removed rules from the API query service; core `ProductServiceImpl.search` owns them. |
| Product LIKE escaping/JDBC helpers | A | Removed copied `likeContains`; inherit shared `BaseDao`, including Unicode parameter binding. |
| Required schema constant | A | `SchemaCompatibilityChecker` uses core `SettingsService.REQUIRED_SCHEMA_VERSION` (1.10.0). |
| Unknown-user `auth.LoginAttemptTracker` | B | Retained API implementation: it adds a 50,000-entry memory bound absent in core. Limits, normalization and expiry are parity-tested. |
| Authentication orchestration, `AuthUserRepository` / `UserDao` SQL | C | Retained Phase 1 login counters, hash upgrade, last-login, state projection and DB-clock lock logic. Core `AuthServiceImpl` is excluded and depends on Desktop `SessionManager`; replacing it would expand scope or disturb existing security behavior. |
| Audit insertion (`AuditLogRepository` / `AuditLogDao`) | C | Retain API authentication audit, including request client address and quiet failure handling. Shared mutation audit remains core-owned when separately authorized. |
| Product count/page projection SQL | B | Retained bounded API paging/sort projection behind the core DAO adapter; immutable core has no paged method. No duplicated product business policy remains here. |
| Schema probing / Desktop health checks | B | API readiness/startup infrastructure probes exact schema and required endpoint tables; Desktop server-admin health classes are excluded. |
| CredentialPolicy / password-change rules | A | Phase 2 uses core `CredentialPolicy.validateNewPassword` and `PasswordHasher`; no duplicate policy or hashing. |
| JWT, principal loading, Spring Security, HTTP validation/DTO/error model | B | API transport/security infrastructure; must remain outside core. Request bounds are HTTP-specific, not duplicated business validation. |

## 3. Trust boundaries

| Zone | Trusted? | Notes |
|---|---|---|
| Mobile device / Flutter app | **No** | Can be modified, replayed, scripted. UI checks are convenience only. |
| Network between app and server | **No** | HTTPS only in production (§17). |
| Reverse proxy (TLS termination) | Yes (operated by us) | The only component exposed to the Internet. |
| Spring Boot API | Yes | Decides every authentication and authorization question. |
| SQL Server | Yes | Reachable **only** from the API host and the desktop LAN — never from the Internet. |

Everything coming from the client — body, query, headers, token claims beyond the verified identity — is validated
or ignored. Role, permissions, active flag and must-change-password are **re-read from the database on every
request**, never taken from the token.

## 4. Why Flutter never connects directly to SQL Server

* A direct connection needs SQL credentials on the phone — extractable from any installed app.
* SQL Server would have to be exposed to the Internet (brute force, unpatched-protocol attacks, data exfiltration).
* Business rules (atomic posting, stock never negative, credit limits, permissions, audit) would run on the client,
  where they can be skipped; two concurrent clients could corrupt stock and ledgers.
* The desktop's permission model would be unenforceable: SQL permissions are per table, not per business action.

The API holds the only credentials, exposes only business operations, and runs every rule server-side.

## 5. API package structure (`api/src/main/java/com/almahwar/api`)

| Package | Content |
|---|---|
| `config` | `DatabaseProperties`, `ApiProperties` (validated configuration), `DataSourceConfig` (HikariCP), `OpenApiConfig` |
| `security` | `SecurityConfig`, `JwtConfig`, `TokenService`, `UserPrincipalLoader`, `ApiUser`, `CurrentUser`, `PasswordChangeRequiredFilter`, `SecurityErrorHandlers`; hashing and permissions come from core |
| `auth` | `AuthController`, `AuthService` (login rules), `AuthUserRepository`, `LoginAttemptTracker`, `dto/` |
| `audit` | `AuditLogRepository` (writes the desktop's `Audit_Log`) |
| `session` | `ApiSessionRepository`, `ApiSessionSchemaRepository`, `SessionService`, `RefreshTokens`; separate API pool and transactions |
| `product` | proof of concept: `ProductController` → `ProductQueryService` → `ProductRepository`, `ProductResponse`, `ProductSort` |
| `health` | `HealthController`, business/API schema compatibility checkers, `StartupSchemaVerifier`, `DatabaseStatusRepository` |
| `error` | `ApiError`, `ErrorCode`, `ApiException`, `FieldValidationException`, `GlobalExceptionHandler`, `ErrorResponseWriter` |
| `web` | `RequestIdFilter` (correlation id), `PageQuery`, `PageResponse` |

Rules (enforced by `ApiArchitectureTest`): controllers contain no SQL and no JDBC / repository access; SQL is written
only in `*Repository` classes; every route is under `/api/v1`; no JPA / Hibernate; no Flutter / Dart files.

## 6. Configuration

Precedence (Spring Boot): command-line › environment variables › `config/application.properties` next to the working
directory (git-ignored) › bundled `application.properties` (safe defaults, **no credentials**).

| Setting | Environment variable | Default |
|---|---|---|
| `almahwar.db.host` / `port` / `name` | `ALMAHWAR_DB_HOST` / `_PORT` / `_NAME` | `localhost` / `1433` / `AlMahwarDB` |
| `almahwar.db.user` / `password` | `ALMAHWAR_DB_USER` / `ALMAHWAR_DB_PASSWORD` | **required**, no default |
| `almahwar.db.encrypt` / `trust-server-certificate` | `ALMAHWAR_DB_ENCRYPT` / `ALMAHWAR_DB_TRUST_SERVER_CERTIFICATE` | `true` / `false` |
| `almahwar.api.db.host` / `port` / `name` | `ALMAHWAR_API_DB_HOST` / `_PORT` / `_NAME` | `localhost` / `1433` / `AlMahwarApiDB` |
| `almahwar.api.db.user` / `password` | `ALMAHWAR_API_DB_USER` / `_PASSWORD` | **independently required**, no business fallback |
| `almahwar.api.db.initialize` | `ALMAHWAR_API_DB_INITIALIZE` | false; explicit empty-catalog baseline opt-in |
| `almahwar.api.jwt.secret` | `ALMAHWAR_API_JWT_SECRET` | **required**: base64 of ≥ 32 random bytes |
| `almahwar.api.jwt.access-token-ttl` | `ALMAHWAR_API_JWT_ACCESS_TOKEN_TTL` | `15m` (max 1 h) |
| `almahwar.api.login.max-attempts` / `lock-seconds` | … | `5` / `300` — **must equal the desktop's** `security.login.*` |
| `almahwar.api.cors.allowed-origins` | `ALMAHWAR_API_CORS_ALLOWED_ORIGINS` | empty (no browser origin) |
| `server.port` | `ALMAHWAR_API_PORT` | `8085` |

The API refuses to start when a required value is missing, a value is out of range, the JWT secret is short /
not base64 / not random, a CORS origin contains `*`, or the database is incompatible (§18). Configuration objects
redact secrets in `toString()`. Template: `api/config/application.example.properties`.
`spring.sql.init.mode=never`: Spring's generic script initializer is disabled. No business schema scripts run.
Only the explicitly enabled API-owned versioned baseline may initialize the separate API catalog.

**Database account.** The API must not run as `sa` in production. Create a dedicated login with only what the
implemented endpoints need (extend per phase):

```sql
-- run by a DBA, once per environment; password from a secret store, never committed
CREATE LOGIN almahwar_api WITH PASSWORD = '<from secret store>', CHECK_POLICY = ON;
USE AlMahwarDB;
CREATE USER almahwar_api FOR LOGIN almahwar_api;
GRANT SELECT ON dbo.Schema_Info TO almahwar_api;
GRANT SELECT ON dbo.Roles TO almahwar_api;
GRANT SELECT ON dbo.Users TO almahwar_api;
GRANT UPDATE (failed_login_attempts, locked_until, last_login_at, password_hash, updated_at,
              must_change_password, password_changed_at) ON dbo.Users TO almahwar_api;
GRANT INSERT ON dbo.Audit_Log TO almahwar_api;
GRANT SELECT ON dbo.Products TO almahwar_api;
GRANT SELECT ON dbo.Categories TO almahwar_api;
GRANT SELECT ON dbo.Units TO almahwar_api;
GRANT SELECT ON dbo.Brands TO almahwar_api;
```

(Phase 1 verification used the existing development login on the local development container only.)

## 7. Authentication design

`POST /api/v1/auth/login` `{ "username", "password", "deviceLabel"? }` → `200 { accessToken, tokenType: "Bearer",
expiresIn, expiresAt, mustChangePassword, user, refreshToken, sid, refreshExpiresAt, absoluteExpiresAt }`,
`Cache-Control: no-store`. Required-change login returns null refreshToken/refreshExpiresAt.

`AuthService.login` follows the desktop's `AuthServiceImpl.login` step for step:

1. Unknown username → in-memory throttling with the same limits, a dummy PBKDF2 verification (equal timing), audit
   `LOGIN_FAILED` (user NULL), answer `401 INVALID_CREDENTIALS`.
2. Account locked (`Users.locked_until` in the future, server clock) → `429 ACCOUNT_LOCKED` + `Retry-After`, even with
   the correct password; audit.
3. Wrong password → the desktop's atomic `UPDATE … OUTPUT` on the Users row (count, lock at 5 for 300 s); audit
   `LOGIN_FAILED` and, when it locks, `ACCOUNT_LOCKED`; answer `401 INVALID_CREDENTIALS` (or 429 when it locked).
4. Correct password but disabled → `403 ACCOUNT_DISABLED`; role without permissions (e.g. `MANAGER`) →
   `403 NO_PERMISSIONS`. Both only after a correct password, as on the desktop.
5. Success → reset counter, CAS-upgrade an old-iteration hash, set `last_login_at`, recheck current credentials,
   persist a session, issue credentials and audit `LOGIN`.

Unknown user and wrong password produce byte-identical answers (no "attempts left" hint). The login is deliberately
**not transactional**: the failure counter and audit rows must persist although the request fails.
Audit entries carry `machine_name = 'API/<client address>'`, so desktop screens show API activity distinctly.
Passwords are converted to `char[]` and wiped after use; DTOs redact them in `toString()`; nothing logs request bodies.

**Interaction with the desktop lockout (no bypass):** the lock lives on the shared Users row and is checked/updated
with the same SQL by both clients — 5 wrong passwords split across desktop and API still lock the account for both.
Unknown usernames are throttled per process (desktop and API each), as today.

**Brute force / rate limiting.** Per-account protection is the shared DB lock. At most *N* (CPU count) PBKDF2
verifications run at once; further logins wait ≤ 5 s, then get `429 TOO_MANY_REQUESTS` (protects the CPU: 600,000
iterations per attempt). **Before the API is reachable from the Internet**, add per-IP rate limiting at the reverse
proxy (e.g. nginx `limit_req` on `/api/v1/auth/login`) — no third-party library was added for this in Phase 1.
Note that the per-account lock can be abused to lock out a known user (also true on the desktop); per-IP limits
mitigate it.

## 8. Authorization design

* Every request except `GET /api/v1/health`, `GET /api/v1/health/ready`, `POST /api/v1/auth/login`,
  `POST /api/v1/auth/refresh` (and CORS
  pre-flight) requires a valid bearer token — deny by default, including unknown paths (401).
* The token's user is reloaded from the database per request (`UserPrincipalLoader`): deleted / disabled user or a
  changed password ⇒ `401 SESSION_REVOKED`; a changed role applies at once.
* Permissions are the desktop's fine-grained `Permission` set for the role, exposed as authorities `PERM_<NAME>`.
* Checks live in the **service layer** (`@PreAuthorize("hasAnyAuthority('PERM_PRODUCTS_VIEW','PERM_PRODUCTS')")`),
  plus data-level rules inside the service (cost only with `PRODUCT_COST`; inactive products only with `PRODUCTS`) —
  mirroring the desktop's `security.requirePermission(...)` in its services.
* No new roles. `ADMIN`, `ACCOUNTANT`, `CASHIER`, `STOREKEEPER` as on the desktop; `MANAGER` has no permissions.
* `GET /api/v1/auth/me` returns the permission list so the app can hide actions — a convenience, never a control.

## 9. Password compatibility

Stored format (unchanged): `pbkdf2_sha256$<iterations>$<base64 salt 16 B>$<base64 key 32 B>`,
PBKDF2-HMAC-SHA256, 600,000 iterations. The API verifies existing hashes as they are; it never migrates or rewrites
hashes except the desktop's own rule (a hash with fewer iterations is re-hashed on successful login, which does not
change `password_changed_at`). `PasswordCompatibilityTest` cross-verifies with the frozen desktop class; the
SQL Server integration test logs in users whose hashes were produced by the desktop class; the live smoke test logged
in real desktop-created accounts.

## 10. Token lifecycle

**Threat model.** A stolen access token lets its holder act as the user until it expires; a stolen signing key lets
anyone mint tokens. Mitigations: short lifetime, HTTPS only, key only in server configuration, per-request user
reload (disable / password change / role change take effect immediately), no sensitive claims.

| Aspect | Phase 2 |
|---|---|
| Format | JWS (JWT) HS256, Nimbus JOSE via Spring Security — no custom crypto |
| Claims | `sub`, `sid` (random UUID), `iss`, `aud`, `iat`, `exp`, `jti`, `pwv` (business password timestamp version) |
| Not in the token | password, hash, role, permissions, names |
| Lifetime | 15 min (configurable, max 1 h); 60 s clock skew |
| Validation | signature, algorithm (HS256 only, `alg:none` rejected), expiry, issuer, audience, then DB state |
| Revocation | Every request checks live user/pwv, server-only credential fingerprint, and indexed live sid with both expiries. Role permissions come from core. |
| Refresh | Opaque `amr_` + 32 SecureRandom bytes; SHA-256 only persisted; single-use rotation, replay revokes the entire sid. Idle 8 hours, absolute 7 days. |
| Logout | Current sid or all own sessions revoked immediately; authenticated self-service list/revoke endpoints. |
| Key rotation | change `ALMAHWAR_API_JWT_SECRET` → all tokens invalid (users log in again) |

**Phase 2 implementation:** [design and verification report](API_PHASE2_AUTH_REPORT.md).
AlMahwarDB remains business schema **1.10.0**, untouched. API sessions live in **AlMahwarApiDB schema 1.0.0**,
with independent `ALMAHWAR_API_DB_*` credentials and pool. There is no duplicate Users table or cross-database FK.
DBA provisions the catalog explicitly; `ALMAHWAR_API_DB_INITIALIZE=true` installs the versioned baseline only on
an empty API catalog, under a transaction-owned SQL application lock. Default startup verifies version, script
checksum and required tables. Unknown/partial/incompatible schemas are rejected without repair. No migration
dependency was added. Startup and readiness require both databases; authentication fails closed on either outage.

Restricted login returns access + sid, **no refresh token**, and permits exactly GET `/auth/me`, POST
`/auth/change-password`, POST `/auth/logout`. Password change reuses core rules and hashing, commits business
credentials first, clears must-change, advances pwv, then revokes all API sessions including the current one.
204 means sign in again. If API revocation fails after the credential commit, the response is 503; the password
has changed, and live pwv/fingerprint checks independently reject old access and refresh credentials.

Session mutations serialize per business user with SQL `sp_getapplock`, not Java synchronization. All live-family
refresh hashes are retained for replay detection; maintenance deletes terminal families only after 30 days, in
batches of 500 (`api/database/cleanup_sessions.sql`). Clients must serialize refresh requests; replay has no grace.
Logout completes with 204; repeating with its now-revoked bearer returns 401. Own/unknown/foreign session DELETE
returns 204 without revealing ownership. Labels are optional, trimmed, bounded to 100, and never trusted.

Production rejects sa and unencrypted/unverified DB connections. Only explicit `dev` allows self-signed DB
certificates. HTTPS remains mandatory at the reverse proxy; no cookies, refresh URLs or credential body logs.

## 11. Error model

One JSON shape for every error (`ApiError`):

```json
{ "timestamp": "2026-10-07T10:15:30Z", "status": 400, "code": "VALIDATION_ERROR",
  "message": "البيانات المرسلة غير صحيحة.", "path": "/api/v1/products", "requestId": "…",
  "fieldErrors": [ { "field": "size", "message": "الحد الأقصى 100." } ] }
```

Codes (`ErrorCode`): `VALIDATION_ERROR`, `MALFORMED_REQUEST` (400) · `UNAUTHORIZED`, `INVALID_CREDENTIALS`,
`SESSION_REVOKED` (401) · `ACCOUNT_DISABLED`, `NO_PERMISSIONS`, `PASSWORD_CHANGE_REQUIRED`, `FORBIDDEN` (403) ·
`NOT_FOUND` (404) · `METHOD_NOT_ALLOWED` (405) · `UNSUPPORTED_MEDIA_TYPE` (415) · `ACCOUNT_LOCKED`,
`TOO_MANY_REQUESTS` (429, `Retry-After`) · `INTERNAL_ERROR` (500) · `SERVICE_UNAVAILABLE` (503, database down —
including a transaction that cannot obtain a connection).

Clients branch on `code`, never on `message` (Arabic, for display). Responses never contain stack traces, SQL,
database messages, class names, file paths, hosts or credentials (tested); details are logged server-side under the
`requestId`, which is also returned in the `X-Request-Id` header.

## 12. DTO rules

* Requests and responses are dedicated records (`LoginRequest`, `LoginResponse`, `CurrentUserResponse`,
  `ProductResponse`, `PageResponse`) — never database rows or desktop models.
* No hash, salt, lock counters or other security internals in any response (tested).
* Money and quantities are JSON **strings** with the database scale (`"12.500"`) — exact, no floating point on clients.
* Fields that depend on permissions are omitted when not allowed (`purchasePrice`); the repository does not even
  select the cost for users without `PRODUCT_COST`.

## 13. Transaction rules

* Shared business transactions are owned exclusively by the core `TransactionManager`; do not wrap core calls in Spring `@Transactional`. Product reads use short auto-commit pool connections; count/page are separate reads, without a snapshot guarantee (the previous READ COMMITTED transaction also did not promise a snapshot).
* Every future business mutation (post sale, post purchase, return, payment, stock adjustment) runs as **one**
  read-write transaction in one service call: the client never coordinates several calls into one business result.
* Lock order must follow the desktop's (`ProductDao.lockForStockChange`: `UPDLOCK, ROWLOCK`, `product_id` ascending)
  to reduce lock-order conflicts. Document-number range locks still have the known SQL Server 1205 limitation below.
* Audit entries for a mutation are written in the same transaction (desktop rule); authentication audit is the
  exception (written outside any transaction so failures persist).
* Idempotency keys for posting endpoints (mobile retries over flaky networks) are planned for the mutation phases.

## 14. Pagination

`page` (0-based, ≤ 10,000), `size` (1–100, default 20), `sort` = allow-listed field with optional `,asc|,desc`
(products: `name`, `code`, `salePrice`, `quantity`). Response: `{ items, page, size, totalItems, totalPages }`.
Bounds are Bean Validation constraints (400 with `fieldErrors`); the sort maps to constant SQL with a unique
tie-breaker (`product_id`), so client text never reaches `ORDER BY`; paging uses bound
`OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`. Search text is matched literally (`%`, `_`, `[` escaped as on the desktop).

## 15. CORS

Native mobile apps are not subject to CORS. Browser origins (Flutter Web, tools) must be listed exactly in
`almahwar.api.cors.allowed-origins`; empty (default) = no cross-origin browser access; `*` is refused at startup;
credentials (cookies) are not allowed — tokens travel in the `Authorization` header.

## 16. Logging

* Every line carries the request id (`%X{requestId}`); the id is taken from a safe caller `X-Request-Id`
  (`[A-Za-z0-9-]{8,64}`) or generated — no log injection.
* Never logged: request bodies, passwords, hashes, tokens, `Authorization` headers, JWT secret, DB password
  (live-run log scanned: none present). Login success logs only user id and role.
* Unexpected / database errors are logged with stack trace server-side only.

## 17. HTTPS and deployment assumptions

* Production: the API listens on a private interface; a reverse proxy (nginx / IIS ARR / Caddy) terminates TLS
  (TLS 1.2+), redirects HTTP → HTTPS, sends HSTS, and applies per-IP rate limits. Plain HTTP is acceptable only on a
  developer machine.
* Behind the proxy, set `server.forward-headers-strategy=framework` **only** together with a trusted-proxy
  configuration, so the client address in audit entries is correct and cannot be spoofed.
* SQL Server traffic stays encrypted (`encrypt=true`, certificate validated; `trust-server-certificate=true` only for
  a development server).
* No cloud deployment in Phase 1.

## 18. Database compatibility

At startup (`StartupSchemaVerifier`, before the HTTP port opens) and on `GET /api/v1/health/ready`:
connection works · database exists and is accessible · `dbo.Schema_Info` exists · version is **exactly** `1.10.0` ·
required tables exist (`Schema_Info, Users, Roles, Audit_Log, Products, Categories, Units, Brands`; grows per phase).
Any failure stops startup with a clear log message and **nothing is created, repaired or migrated**. Readiness
answers only `READY` / `NOT_READY` (no details), checked at most every 5 s. Schema changes needed by later phases
are listed in §20 and require an explicitly approved migration.

## 19. Testing strategy

Run from `api/`: `mvn verify` (no database needed). SQL Server integration: install the desktop artifact once
(`mvn install` in the repository root — required runtime core dependency), then set `ALMAHWAR_IT_DB_HOST`, `_PORT`,
`_USER`, `_PASSWORD`, `_TRUST_SERVER_CERTIFICATE` and run `mvn verify -Dalmahwar.it=true`.

| Suite | What it proves |
|---|---|
| `HealthAndErrorModelTest` | context starts; liveness/readiness; 401 format; 404/405/415; malformed JSON; request id; security headers; OpenAPI off by default |
| `AuthenticationApiTest` | login success; same answer for unknown user / wrong password; lock at limit; locked with correct password; unknown-user throttling; disabled only after correct password; MANAGER refused; hash upgrade; validation; forged / expired / wrong-audience / wrong-issuer / `alg:none` tokens; revocation on disable / password change / delete; DB down → 503; must-change-password gate |
| `ProductApiTest` | 200 per role; cost hidden / shown; inactive rule; validated paging & sort (injection attempts); 403; DB errors leak nothing |
| `DatabaseUnavailableTest` | real pool + core JDBC adapter, DB down → safe 503 |
| `DevProfileAndCorsTest` | OpenAPI in `dev` profile; CORS exact origin, no credentials |
| `ConfigurationValidationTest` | missing / weak secrets and credentials stop startup; secrets never printed; bundled defaults clean |
| `PasswordCompatibilityTest`, `PermissionMatrixParityTest`, `LoginAttemptTrackerTest` | shared core 1.0.1 against frozen Phase 1 grants / independent stored-format vectors; retained throttle parity |
| `SchemaCompatibilityCheckerTest` | exact 1.10.0; missing tables; unreachable / 4060; startup refusal |
| `ProductSortTest`, `ApiArchitectureTest` | allow-list, LIKE escaping, page bounds; layering, versioned routes, no JPA / Flutter |
| `SqlServerIntegrationTest` (opt-in) | real SQL Server on a **temporary database** built from `database/01_create_database.sql` and dropped afterwards: desktop-hashed users log in, shared lock on the row, audit rows, cost visibility, paging/sort/search, hash upgrade, revocation, schema mismatch refused at startup |

AlMahwarDB is never used by automated tests.

## 20. Planned API phases (adjusted to the codebase)

The released Desktop 1.0.1 core is adopted; Phase 2 authentication is implemented for review. Flutter has not started.
The planned mobile application is **Al Mahwar Manager**, not a mobile POS; no mobile sales creation is currently
planned. Future business endpoints require separate approval and must use the shared core.

| Phase | Scope | Notes / prerequisites |
|---|---|---|
| 1 | Foundation, security, health, POC (this phase) | done — awaiting review |
| 2 | Auth completion: password change, logout, sessions, refresh | **Implemented for review** on `api-phase2-auth`; separate API database 1.0.0, no business schema migration. |
| 3 | Manager dashboard, sales analytics, expense/cash book summaries, inventory/product history, customers/suppliers and accounts | **Implemented for review** on `api-phase3-manager-read`; GET-only, bounded, live released permissions; no schema changes. |
| 4 | Next Manager scope | Not started; requires separate approval after Phase 3 acceptance. Dedicated barcode/reference lists and other read enhancements remain candidates. |
| 5 | Future document posting (separate approval; no mobile sales currently planned) | one transaction per posting, desktop lock order, idempotency keys, credit limit & price-override rules |
| 6 | Purchases | same pattern as 5 |
| 7 | Financial operations (cashbox, expenses, payments) | balanced ledger entries as on the desktop |
| 8 | Returns, quotations | against original documents; status workflow |
| 9 | Reports (read-only, paged / aggregated; Excel export later) | heavy queries: limits and timeouts |
| 10 | Production hardening & deployment | reverse proxy + TLS, per-IP rate limits, least-privilege login, monitoring, key management, load test, backup of secrets |

Flutter work starts only after the API contract and security foundation are accepted.

## Known limitations (Phase 2)

* Cross-database credential change and session revocation are separate commits. Live credential checks close
  the security gap; a 503 after password change may mean the new password already committed.
* Restoring either authentication-related database can restore earlier security/credential state: revoke every API
  session after restore.
* Login rehash upgrades also invalidate older API sessions via their credential fingerprint (safe re-login required).
* No per-IP rate limiting inside the API (planned at the reverse proxy).
* The unknown-username throttle is per API process (as on the desktop, per desktop process).
* The password arrives as a JSON string, which cannot be wiped from memory (converted to `char[]` and wiped after).

* **Document numbering blocker for future concurrent mutation endpoints:** inherited from v1.0.0, released v1.0.1
  uses `SELECT MAX(...) WITH (UPDLOCK, HOLDLOCK)` and can hit SQL Server error 1205 under concurrent document
  creation. Transactions roll back completely. No fix is attempted here; a separately approved numbering/retry
  design must precede high-concurrency document creation. Read-only product adoption is unaffected.
* The process-wide core connection provider supports one API application per JVM; the production API and Desktop
  run in separate JVMs.

### Adoption verification additions

`CoreAdaptersTest` covers identity/permission mapping, must-change restrictions, absent/unauthenticated principals,
thread isolation, connection lifecycle and rollback. `ProductCoreAuthorizationTest` bypasses Spring proxies to
prove core denial and visibility. `SharedCoreRuntimeTest` checks the actual 1.0.1 classifier, excluded UI/classes,
JavaFX absence and no Spring transaction wrapper. `SqlServerIntegrationTest` also starts a real HTTP server on a
random port, checks health/readiness/products, verifies Hikari borrow/return and real SQL rollback for runtime and
SQL exceptions. Tests use a unique `AlMahwarApiIT_*` database built from the existing script and drop it afterward.
Desktop `CoreBoundaryTest`, `ConnectionSeamTest` and provider golden tests remain the release authority.

### Phase 3 Manager read boundary

`manager.ManagerController` delegates to `ManagerQueryService`. Both require permissions through
`ManagerAccess`, which reads `SpringSecurityContext` and the released role matrix. Phase 2 sid, credential,
pwv, user state, revocation and must-change checks run before Manager access. No role grants were added.

`ManagerAnalyticsRepository` calls the released `DashboardDao` and `ReportDao` for financial calculations.
API SQL supplies grouped bounded trend buckets and a slow-stock page with a unique tie-breaker matching
the released eligibility rule. Catalog and party repositories use core detail services and shared `BaseDao`
connections, Unicode binding and literal LIKE escaping. API projections add pagination/safe sort, selective
cost/balance columns and paged ledger running balances where core methods are unbounded. Repositories
perform no business writes. Authentication retains its existing audit/last-login/session writes.

Money and quantities are three-decimal strings. Financial cost comes from historical document costs;
current product cost is used only for inventory valuation. Optional cost/profit/balance fields are omitted
without their specific permissions. Account balances keep customer debit-minus-credit and supplier
credit-minus-debit signs. Receivables/payables include inactive parties with positive ledger debt.

Business periods use SQL Server local accounting dates, preserving Desktop behavior; deploy that clock
in Kuwait business time. Inclusive dates become half-open SQL predicates. Lists default to 20, max 100;
page max 10000 and q max 100. Most date ranges max 366 inclusive days; weekly/monthly trends max 1096.
Manager responses use `Cache-Control: no-store`. Connection acquisition failures and connection-class
SQL failures during Manager reads produce safe 503 errors; other SQL failures remain safe 500 errors.

Normal released isolation applies. Separate count/page, opening/totals/history and sales/profit statements
can observe concurrent changes; no cross-statement snapshot consistency is promised. Existing indexes
only are used. Query-plan evidence, recommended future measurements and the full test results are in the
Phase 3 report. Schema versions remain business 1.10.0 and API sessions 1.0.0.

### Phase 4 Manager administration boundary

`admin.AdminController` provides bounded user list/detail, creation, explicit full-profile PUT,
enable/disable, administrative password reset, and read-only role/permission metadata under
`/api/v1/manager/admin`. `AuditController` adds GET `/api/v1/manager/audit`. Controller and service
checks use released USERS_VIEW/CREATE/EDIT/RESET_PASSWORD and AUDIT_LOG permissions through the
existing SpringSecurityContext intersection. These permissions belong only to ADMIN in core 1.0.1.

All identity mutations delegate to the released `UserServiceImpl`; its validation, PBKDF2, audit
transactions, self protection and database-locked last-admin invariant remain authoritative.
No API SQL writes Users. No Desktop/core files, schemas or dependencies change. DTOs omit hashes,
credential versions/fingerprints, lock/failure internals and session secrets. Create/reset require
administrator-supplied temporary passwords, must-change=true, and never return the password.
Username is immutable. PUT requires fullName, roleCode and explicit active state; phone/email may
be null. It is full replacement with normal core last-writer semantics, not an optimistic PATCH.
The existing mapper's unknown-field behavior is retained; extra fields have no binding to core models.

`AdminSessionRepository` holds a SQL session-owned application lock on the existing Phase 2 per-user
resource. Core identity changes and API session revocations commit separately. Enable and active PUT
commit revocation before the core change, then revoke again; disable/reset revoke after the core change.
No login/refresh session mutation can cross the lease. A request already authenticated may complete;
subsequent requests reload live identity, permissions and credential fingerprints. A 503 may mean the
identity change already committed: clients must re-read state before retrying. Pre-revocation may
sign out the target even if core rejects an active PUT. Session locks are explicitly released; a failed
release aborts/evicts the connection. This is API orchestration, not a distributed transaction.

User lists use literal username/full-name search, assignable role/active filters and name/username/created
sorts with id tie-breakers. Audit uses inclusive SQL-local dates (maximum 366 days), userId/action/category
filters and fixed timestamp/id descending order. Both reuse page 0..10000, size 1..100, default 20.
Audit responses select only id/time/actor/event/category; free-form descriptions, record ids, old/new JSON
and machine metadata are excluded. Unknown historical action/category values become OTHER. There is
no invented success/failure field. Existing date/user indexes are used; separate count/page statements
can observe concurrent changes, and broad filters/deep pages still require production measurements.

Administration and audit responses reuse Manager no-store and safe connection-error mapping. Core
validation errors return safe fixed Arabic field messages with request IDs, without internal exception
text. Existing own-session/device routes remain unchanged; no broad admin device-surveillance or
permanent user-deletion endpoint exists. Reverse-proxy rate limits for costly create/reset operations
remain a deployment recommendation. The full authority map and verification are in
`API_PHASE4_MANAGER_ADMIN_REPORT.md`.

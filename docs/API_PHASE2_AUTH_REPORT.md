# API Phase 2 — authentication and device sessions

Implementation and verification report, 2026-10-07. Branch: `api-phase2-auth`.
Review state: **API PHASE 2 AUTHENTICATION READY FOR REVIEW**. All required verification passed.
No commit, tag, push or merge; all Phase 2 changes remain unstaged and uncommitted.

## Release and database boundaries

| Reference | Exact value |
|---|---|
| Starting commit / HEAD / local and remote `api-core-adoption` | `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99` |
| `main`, `origin/main`, peeled `v1.0.1` | `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b` |
| `api-development` | `5cd2819eb2495123c1a41b5db324c267f389d5ee` |
| Peeled `v1.0.0` | `c27b2e36c2f05596c86cc07d13f59b82122c3945` |
| Shared core | `com.almahwar:almahwar-store-management:jar:core:1.0.1` |
| Business catalog / schema | `AlMahwarDB` / **1.10.0**, unchanged |
| API security catalog / schema | `AlMahwarApiDB` / **1.0.0**, independent lifecycle |

Both Phase 1 and the released Desktop commit are ancestors of the starting adoption commit. Phase 2 adds no
Desktop source, build, resource, configuration or business-schema changes. The Desktop has no dependency on the
API catalog. No business catalog was created, restored, wiped or migrated during this work. The unrelated empty
root `docker` file remains untracked and unchanged.

## Persistence and initialization

The existing Spring JDBC, SQL Server driver and Hikari dependencies suffice; no Flyway, Liquibase, JPA or other
dependency was added. The business datasource remains primary, so the core connection binding and business
JdbcClient continue using the business pool. Session repositories explicitly select `sessionDataSource` and
construct a transaction manager for that datasource only.

The DBA deliberately provisions the API catalog using `api/database/00_create_api_database.sql`. Runtime startup
never creates databases. The separate initializer accepts only the configured `AlMahwarApi*` catalog, rejects
the business catalog, and takes a database transaction-owned schema application lock. Explicit
`ALMAHWAR_API_DB_INITIALIZE=true` installs `db/api/V1__sessions.sql` only when there are zero user tables.
DDL and the schema-version insert share one transaction. Otherwise startup verifies the baseline, never repairs
an unknown/partial schema. Initialization is idempotent on the installed version. A mismatched version or script
checksum fails startup. The checksum is SHA-256 of UTF-8 SQL normalized to LF, independent of Git checkout endings.

| Object | Contents / indexes |
|---|---|
| `dbo.api_schema_version` | Singleton constrained id=1; version, script checksum, installed UTC timestamp. Primary key. |
| `dbo.api_sessions` | Internal clustered identity; random UUID `sid` unique nonclustered index; business user id; timestamp pwv; server-only credential fingerprint; restricted flag; optional label; created/activity/idle/absolute/revocation UTC timestamps; safe reason. Indexes on user and retention timestamps; idle <= absolute constraint. |
| `dbo.api_refresh_tokens` | Internal identity primary key; sid foreign key with cascade; unique BINARY(32) SHA-256 token hash; issued/consumed UTC timestamps; sid history index. |

There is no API Users table, copied username/role/password hash, cross-database foreign key, persisted access JWT,
or plaintext refresh token. The credential fingerprint is a server-only SHA-256 digest of the current stored
business password hash. It detects Desktop resets that happen within the same second as another credential
change, because the frozen business `password_changed_at` column is DATETIME2(0). It never appears in JWTs or HTTP
session responses. A login rehash changes the fingerprint and therefore invalidates earlier API sessions too.

## Token and session lifecycle

Access tokens remain HS256 JWS with issuer, audience, signature and expiry validation, mandatory iat/exp, weak-key
rejection, and a default 15-minute lifetime (configurable from 1 second to 1 hour; existing 60-second JWT skew).
Claims are exactly `sub`, `sid`, `pwv`, `iat`, `exp`, `iss`, `aud`, `jti`. No role/permission list or credential data
is trusted from claims. Each request reloads the business user and checks active/existence/pwv, the credential
fingerprint, sid ownership, revocation and both session expiries. Current role permissions come from released core.
Neither live user state nor access-session validity is cached.

Refresh tokens are `amr_` followed by 43 canonical base64url characters from 32 SecureRandom bytes: 256 random bits,
no padding. Only SHA-256 of the complete canonical token enters persistence. Raw tokens are returned in JSON over
HTTPS; clients must put them in secure device storage and omit them from URLs, telemetry and body logs.

SQL Server UTC establishes session timestamps. Normal sessions expire after 8 hours without refresh and absolutely
7 days after creation. A successful refresh updates activity and sets idle expiry to min(DB UTC now + 8 hours,
original absolute expiry); absolute expiry never moves. Activity means successful login/refresh, not every product
read. Session listing reports ACTIVE, EXPIRED or REVOKED from database-clock state.

Every session mutation takes an exclusive SQL application lock scoped to business user and API catalog. A table
SELECT first opens exactly one JDBC implicit transaction before acquiring the transaction-owned lock; an explicit
BEGIN under implicit mode would nest transactions. This follows Microsoft's
[implicit transaction semantics](https://learn.microsoft.com/en-us/sql/t-sql/statements/set-implicit-transactions-transact-sql?view=sql-server-ver17)
and is checked against SQL Server, including transaction depth, rollback and lock release.

Refresh resolves the hash to a user, acquires that user's lock, then re-reads token/session state. It checks current
business state under the API lock, consumes the old hash conditionally, inserts the replacement hash and updates
idle expiry in one transaction. Used hashes remain in the family. Replaying any consumed hash revokes the entire
sid, including its replacement, and commits that revocation before the generic 401 is thrown. A safe local security
event and best-effort business audit identify user/sid only. There is no grace period; clients must single-flight
refresh. In concurrent refresh, exactly one response succeeds; replay by the loser invalidates the winner's family.
An in-flight response can arrive after revocation, but its tokens cannot restore access.

## HTTP contract

All paths below are under `/api/v1/auth`. Token-bearing responses use `Cache-Control: no-store`.
Phase 1 access tokens without sid are rejected after upgrade; existing API clients must sign in again.
Send refresh without an Authorization header; an invalid/expired bearer is rejected by the security filter even
on a public route. The refresh credential belongs only in the request body.

| Method / path | Input | Success / semantics |
|---|---|---|
| POST `/login` | Existing username/password; optional `deviceLabel` | 200; existing six response fields preserved, extended with refreshToken, sid, refreshExpiresAt, absoluteExpiresAt. Creates a tracked session. |
| POST `/refresh` | `{ "refreshToken": "..." }`, public credential endpoint | 200; same token/user response shape, newly rotated access/refresh credentials. All invalid refresh states use generic INVALID_CREDENTIALS 401. |
| GET `/me` | Bearer | Existing current-user contract, including live permission set. |
| POST `/logout` | Bearer; sid comes from principal | 204; revoke current sid only. Persistence is idempotent. Repeating with the revoked bearer returns 401. |
| POST `/logout-all` | Bearer | 204; revoke all this user's sessions including current; the request completes, subsequent authentication fails. |
| GET `/sessions` | Bearer | 200 array: sid, createdAt, lastActivityAt, idleExpiresAt, absoluteExpiresAt, current, deviceLabel, status. Own sessions only. |
| DELETE `/sessions/{sid}` | Bearer; UUID target | 204 for own, unknown or another user's sid. Only owned rows can change; no existence/ownership disclosure. |
| POST `/change-password` | currentPassword, newPassword, confirmPassword | 204; all sessions ended, sign in again. Identity comes only from the principal; client username has no authority. |

Optional labels are trimmed, max 100 UTF-16 characters, and reject control/format characters and angle brackets.
Labels have no security meaning. No user-agent/device fingerprint is collected. Existing bounded audit client
address handling is retained.

Must-change login creates a restricted access session with expiry equal to the configured access lifetime, without
a refresh token or refresh hash. The exact allowlist is GET `/me`, POST `/change-password`, POST `/logout`.
Products and all other paths/methods, including session list/revoke and logout-all, return PASSWORD_CHANGE_REQUIRED.
Restricted principals have no core permissions; clearing the business flag alone cannot promote a restricted sid.

Password change uses shared `CredentialPolicy.validateNewPassword` and `PasswordHasher`: current password proof,
matching confirmation, different new password, 8–128 characters, letters and digits, not the username;
PBKDF2-HMAC-SHA256, 600,000 iterations and random salt in the Desktop's existing stored format. Credential writes
compare the exact prior hash with binary collation, clear must-change/failed attempts/lock, and advance timestamp
pwv monotonically even in the same second. Login rehash also uses hash CAS, preventing a stale login from overwriting
a concurrent Desktop reset. Password buffers are wiped; JSON credential strings remain immutable JVM strings.

## Transactions, outages, audit and operations

No Spring transaction wraps a shared-core business service. Core TransactionManager still owns shared business
transactions. The password mutation is one atomic business UPDATE committed first; API session revocation is a
second transaction. No distributed atomicity is claimed. If API revocation fails afterward, the endpoint returns
503 and the password is already changed: sign in with the new password after recovery. Live pwv/fingerprint checks
independently reject old access and refresh, including during a refresh/password-change race. Eager revocation is
recoverable; the next rejected refresh also revokes its stale family.

Unavailable API persistence prevents session creation, refresh, revocation and access validation; unavailable
business state prevents credential authentication and refresh. No untracked tokens are returned. Connection/lock
acquisition failures map to safe 503 responses. Liveness remains UP; readiness requires both compatible databases
and returns only READY/NOT_READY, with the existing five-second cache. Startup refuses unavailable/incompatible
dependencies. A dependency can change after a successful state read; credentials returned by an in-flight request
still require fresh live checks on every subsequent use.

Audit codes include LOGIN, LOGIN_FAILED, ACCOUNT_LOCKED, API_REFRESH_REUSE, LOGOUT, API_LOGOUT_ALL,
API_SESSION_REVOKED and PASSWORD_CHANGED. Audit writes preserve existing best-effort Desktop semantics; audit
failure does not undo a committed revocation or password change. Reuse also logs a safe identifier-only event.
Exceptions in authentication/database paths log safe types/codes, never exception text containing driver data or
bound credentials. DTOs and internal credential/session records redact secrets in toString. Correlation IDs and
the existing generic API error shape remain intact.

Maintenance principal: run `api/database/cleanup_sessions.sql` regularly, for example hourly until a batch deletes
zero rows. Each call deletes at most 500 sessions terminal for at least 30 days; FK cascade deletes their entire
token histories. No live-family hashes are pruned, and no runtime DELETE or scheduler is required. Without this
operational job terminal history accumulates; deployment must configure it. Monitor growth and set reverse-proxy
login/refresh rate limits. After restoring either authentication-related database, deliberately revoke all API
sessions: an API restore can resurrect revocations, and a business restore can restore older credential versions.

## Configuration and deployment

Configuration precedence remains command line > environment > external git-ignored `config/application.properties`
> bundled safe defaults. Each database needs explicit credentials, even if an operator deliberately configures the
same login for a disposable test. Production should use independent least-privilege SQL principals.

| Environment variable | Meaning / default |
|---|---|
| `ALMAHWAR_API_DB_HOST`, `_PORT`, `_NAME` | Session SQL host/port/catalog; localhost / 1433 / AlMahwarApiDB |
| `ALMAHWAR_API_DB_USER`, `_PASSWORD` | Independently required; no business fallback or credential defaults |
| `ALMAHWAR_API_DB_ENCRYPT` | true; false rejected outside dev |
| `ALMAHWAR_API_DB_TRUST_SERVER_CERTIFICATE` | false; true accepted only with explicit dev profile |
| `ALMAHWAR_API_DB_HOST_NAME_IN_CERTIFICATE` | Optional TLS certificate name override |
| `ALMAHWAR_API_DB_LOGIN_TIMEOUT_SECONDS` | 5, bounded 1–60 |
| `ALMAHWAR_API_DB_MAX_POOL_SIZE` | 10, bounded 1–100 |
| `ALMAHWAR_API_DB_INITIALIZE` | false; deliberate one-time opt-in with a deployment principal |
| Existing `ALMAHWAR_DB_*`, `ALMAHWAR_API_JWT_SECRET` | Business connection and external JWT key remain required |

sa is rejected for either connection, including dev. Production requires encryption and certificate validation
for both databases; self-signed development SQL Server tests explicitly select dev. Runtime has SELECT/INSERT/UPDATE
on session tables and SELECT on the version table; baseline provisioning temporarily needs CREATE TABLE/ALTER dbo.
Remove deployment DDL privileges afterward. Application locks use the database principal's public membership.
HTTPS is mandatory at the trusted reverse proxy; exact-origin CORS, no cookie authentication, no refresh query
parameters, and no credential-bearing request/response logging. No real passwords or keys are committed.

## Verification evidence

Final command: `mvn -B -f api/pom.xml verify -Dalmahwar.it=true`, with temporary non-sa credentials supplied only
through the test process environment. **BUILD SUCCESS: 150 tests, zero failures, errors or skips.**

| Group | Passed | Notes |
|---|---:|---|
| API non-database tests | 105 | HTTP authentication/session/error/health/CORS, configuration, hashing, JWT, core, architecture and Products |
| Phase 2 session SQL | 33 | Real persistence, lifecycle, outages, security and transactions |
| API schema lifecycle SQL | 2 | Empty opt-in, idempotence/checksum, partial schema refusal |
| Existing SQL regressions | 10 | Desktop-compatible login/lockout, audit, Products, HTTP server, schema and core transaction boundaries |
| Total API SQL | 45 | Included in the 150 total; none skipped |
| Database concurrency | 7 | Included in the 33 session SQL tests; extra transaction-depth/rollback test also passed |
| Desktop/shared core | 168 | `mvn -B -o test`: 373 discovered, 205 opt-in tests skipped, zero failures/errors |

All 99 inherited API regression cases (89 non-database + 10 SQL) remain passing, plus 51 added cases.
CoreBoundaryTest 5, ConnectionSeamTest 3, API core adapter/runtime/authorization tests 13, permission parity tests 2,
and ProductApiTest 10 passed. Skipped Desktop opt-in tests are not counted as executed verification.
The existing lockout test's Retry-After bound now allows DATETIME2(0) rounding plus DATEDIFF/+1; lockout SQL is unchanged.

Evidence retained in ignored build output: `api/target/phase2-full-verify.log`, API Surefire XML/text reports,
`target/phase2-desktop-regression.log`, `target/phase2-verification-summary.json`, and SQL cleanup/identity snapshots.
The final packaged JAR contains core 1.0.1 and the new readiness service, excludes JavaFX, and has five empty
bundled credential/key defaults. Production source/config/docs credential-pattern scan and final log scan found
zero private-key/access-key/raw-refresh/JWT/password-hash matches. The final diff whitespace check passed.

SQL test catalogs use random unique `AlMahwarApiIT_*` (business schema copy from the released script) and
`AlMahwarApiSessionIT_*` names. A random temporary non-sa login is explicitly configured for both. Tests delete
their databases and verify disappearance; the harness additionally cleans only catalogs owned by its newly created
login with these prefixes on failure, drops that login, and verifies zero disposable catalogs/logins remain.
The real business catalog is never selected by tests; its identity is checked unchanged by the harness.
Final cleanup verified **zero disposable databases and zero temporary Phase 2 logins**. Test users disappeared with
their catalogs. Temporary verification helpers and intermediate logs were removed; final evidence/build artifacts remain.

Coverage: existing login/lockout/old hashes/Arabic and symbol passwords; JWT valid/expired/signature/issuer/audience/
wrong algorithm/missing expiry/sid; current user/pwv/hash/sid invalidation; rotation, full-history reuse and safe
audit/logs; idle/absolute limits; own-only session list/revoke and unknown/foreign target parity; logout/all;
required/normal password change and Desktop hash compatibility; credential CAS; both database outages and
post-password-commit revocation outage; baseline opt-in/idempotence/checksum/version/partial-schema refusal;
retention/cascade; permission, Products, core and Desktop boundaries.

Database concurrency cases: same-token race (one success, loser revokes family), refresh against logout,
logout-all and password change, plus deterministic blocked-refresh tests for all three revocation cases. Separate
repository instances/connections share SQL locks, never a Java mutex. An extra rollback test checks @@TRANCOUNT=1,
duplicate replacement insert rollback, original token still usable, and zero transaction depth after pool return.

## Requested 64-point review checklist

| # | Item | Result / location |
|---|---|---|
| 1 | Final status | API PHASE 2 AUTHENTICATION READY FOR REVIEW; all required checks passed |
| 2 | Branch | api-phase2-auth |
| 3 | Starting commit | 0aa9f7b9239f6c9a060ba00c8f0309993a9deb99 |
| 4 | main SHA | c0234e49e2bf00e094f2bf5de68bebd7fe5f963b |
| 5 | api-core-adoption SHA | 0aa9f7b9239f6c9a060ba00c8f0309993a9deb99 |
| 6 | v1.0.1 target | c0234e49e2bf00e094f2bf5de68bebd7fe5f963b |
| 7 | Business schema | AlMahwarDB 1.10.0 unchanged |
| 8 | API schema | AlMahwarApiDB 1.0.0 |
| 9 | Init design | Explicit catalog provisioning; opt-in transactional empty-catalog baseline; checksum/version verification |
| 10 | Tables/indexes | Three API tables, unique sid/hash, user/history/retention indexes; detailed above |
| 11 | Session model | Tracked per-login/device family, live checks and safe self-service metadata |
| 12 | sid | UUID.randomUUID; internal identity never used as public session identity |
| 13 | Refresh generation | SecureRandom, 32 bytes, canonical base64url, amr_ prefix |
| 14 | Refresh hashing | SHA-256, BINARY(32), no plaintext persistence |
| 15 | JWT claims | sub,sid,pwv,iat,exp,iss,aud,jti |
| 16 | Access lifetime | Default 15 minutes; configured 1s–1h |
| 17 | Idle lifetime | 8 hours; refreshed with DB UTC |
| 18 | Absolute lifetime | 7 days; never extended |
| 19 | Rotation | SQL user lock + atomic consume/insert/idle update |
| 20 | Reuse | Commit whole-sid revocation, audit, generic 401 |
| 21 | Concurrent refresh | Exactly one success; losing replay revokes replacement |
| 22 | Logout | Current sid revoked; 204 then revoked bearer 401 |
| 23 | Logout-all | All own sessions, including current; 204 completes |
| 24 | Listing | Own safe fields only, current flag and status |
| 25 | Individual revoke | Owned row only; own/foreign/unknown target 204 |
| 26 | Password change | Core policy/hash, credential CAS, commit then revoke all; 204 and re-login |
| 27 | Must-change | No refresh; exact three method/path allowlist; no core permissions |
| 28 | pwv | Live equality; monotonic API changes; server-only fingerprint closes Desktop same-second reset gap |
| 29 | Disabled users | Access/refresh rejected using live business state |
| 30 | Permissions/core | Current released RolePermissions and core service authorization retained |
| 31 | Cross-database consistency | Credential commit authoritative; eager API revocation separate and recoverable |
| 32 | API outage | No session/token issuance; access/refresh fail closed |
| 33 | Business outage | No stale-session authentication; failed refresh transaction rolls back |
| 34 | Health | Both dependencies checked; generic readiness, startup refusal |
| 35 | Audit | Safe existing codes plus API session events; best effort, identifier-only reuse log |
| 36 | Retention | Operator-run bounded 500-family batches, terminal >=30 days; complete live history retained |
| 37 | Variables | Independent ALMAHWAR_API_DB_* listed above; existing JWT/business variables retained |
| 38 | Secret scan | Source/config/docs patterns clear; five source and packaged credential/key defaults empty |
| 39 | Plaintext scan | Hash-only schema and real SQL equality tests; logs captured without credential values |
| 40 | Injection/security | Bound SQL values, validated catalog/host, canonical tokens, bounded safe labels |
| 41 | IDOR | Own-only query/update; HTTP foreign/unknown parity; another user unaffected |
| 42 | Unit totals | 105 non-database API tests passed |
| 43 | SQL totals | 45 passed: 33 sessions + 2 migrations + 10 regressions |
| 44 | Concurrency totals | Seven database concurrency cases; extra transaction rollback case |
| 45 | Regression totals | 99 inherited API cases passed; Desktop/shared core 168 passed, 205 opt-in skipped |
| 46 | Products POC | Existing HTTP, core permission and real SQL tests retained |
| 47 | Shared core | Root unit tests, CoreBoundary 5, ConnectionSeam 3; API adapter/runtime/core authorization tests |
| 48 | Desktop integrity | Source/build/resources/config/business scripts byte-identical to main |
| 49 | AlMahwarDB safety | No live catalog selected or modified; only disposable released-schema copies |
| 50 | Cleanup | Final run verified zero disposable catalogs/logins; test users removed with catalogs |
| 51 | Endpoints | Six added (refresh/logout/logout-all/sessions GET+DELETE/change-password); login extended |
| 52 | Response changes | Existing login fields preserved; four safe session/refresh fields added; restricted refresh null |
| 53 | Dependencies | None added; API pom unchanged |
| 54 | Changed files | Complete list below, with final status snapshot |
| 55 | git status | 48 Phase 2 files changed/new, all unstaged; unrelated docker remains untouched/untracked; snapshot below |
| 56 | No commit | Confirmed; HEAD remains starting adoption commit |
| 57 | No tag | Confirmed; release tags untouched |
| 58 | No push | Confirmed; only prior adoption task was pushed |
| 59 | main untouched | Confirmed ref and protected-path diff |
| 60 | adoption untouched | Local/remote adoption stay at starting commit |
| 61 | Business modules | No Manager API module expansion |
| 62 | Flutter | Not started |
| 63 | SQL 1205 | Existing document-number deadlock limitation unchanged/unfixed; unrelated to sessions |
| 64 | Limitations | Separate commits, maintenance/restore procedure, client single-flight, reverse-proxy rate limits, immutable JSON strings; detailed above |

## Complete changed-file summary

The final status snapshot lists every changed file below. All paths belong to API implementation/tests or its
documentation. No dependency, Desktop source or released business SQL changes are included.

```text
 M api/config/application.example.properties
 M api/src/main/java/com/almahwar/api/audit/AuditLogRepository.java
 M api/src/main/java/com/almahwar/api/auth/AuthController.java
 M api/src/main/java/com/almahwar/api/auth/AuthService.java
 M api/src/main/java/com/almahwar/api/auth/AuthUserRepository.java
 M api/src/main/java/com/almahwar/api/auth/dto/LoginRequest.java
 M api/src/main/java/com/almahwar/api/auth/dto/LoginResponse.java
 M api/src/main/java/com/almahwar/api/config/DataSourceConfig.java
 M api/src/main/java/com/almahwar/api/config/DatabaseProperties.java
 M api/src/main/java/com/almahwar/api/error/GlobalExceptionHandler.java
 M api/src/main/java/com/almahwar/api/health/HealthController.java
 M api/src/main/java/com/almahwar/api/health/SchemaCompatibilityChecker.java
 M api/src/main/java/com/almahwar/api/security/ApiUser.java
 M api/src/main/java/com/almahwar/api/security/JwtConfig.java
 M api/src/main/java/com/almahwar/api/security/PasswordChangeRequiredFilter.java
 M api/src/main/java/com/almahwar/api/security/SecurityConfig.java
 M api/src/main/java/com/almahwar/api/security/SecurityErrorHandlers.java
 M api/src/main/java/com/almahwar/api/security/TokenService.java
 M api/src/main/java/com/almahwar/api/security/UserPrincipalLoader.java
 M api/src/main/resources/application.properties
 M api/src/test/java/com/almahwar/api/AuthenticationApiTest.java
 M api/src/test/java/com/almahwar/api/DatabaseUnavailableTest.java
 M api/src/test/java/com/almahwar/api/DevProfileAndCorsTest.java
 M api/src/test/java/com/almahwar/api/SqlServerIntegrationTest.java
 M api/src/test/java/com/almahwar/api/config/ConfigurationValidationTest.java
 M api/src/test/java/com/almahwar/api/security/JwtConfigTest.java
 M api/src/test/java/com/almahwar/api/support/ApiWebTestBase.java
 M docs/API_ARCHITECTURE.md
 M docs/adr/ADR-001-business-core-and-api-sessions.md
?? api/database/00_create_api_database.sql
?? api/database/cleanup_sessions.sql
?? api/src/main/java/com/almahwar/api/auth/PasswordChangeService.java
?? api/src/main/java/com/almahwar/api/auth/dto/ChangePasswordRequest.java
?? api/src/main/java/com/almahwar/api/auth/dto/RefreshRequest.java
?? api/src/main/java/com/almahwar/api/config/SessionDatabaseProperties.java
?? api/src/main/java/com/almahwar/api/health/ApiSchemaCompatibilityChecker.java
?? api/src/main/java/com/almahwar/api/session/ApiSessionRepository.java
?? api/src/main/java/com/almahwar/api/session/ApiSessionSchemaRepository.java
?? api/src/main/java/com/almahwar/api/session/RefreshTokens.java
?? api/src/main/java/com/almahwar/api/session/SessionService.java
?? api/src/main/resources/db/api/V1__sessions.sql
?? api/src/test/java/com/almahwar/api/SessionApiTest.java
?? api/src/test/java/com/almahwar/api/SessionSchemaInitializationTest.java
?? api/src/test/java/com/almahwar/api/SessionSqlServerIntegrationTest.java
?? api/src/test/java/com/almahwar/api/config/SessionConfigurationTest.java
?? api/src/test/java/com/almahwar/api/session/RefreshTokensTest.java
?? api/src/test/java/com/almahwar/api/support/TemporaryApiDatabase.java
?? docker
?? docs/API_PHASE2_AUTH_REPORT.md
```

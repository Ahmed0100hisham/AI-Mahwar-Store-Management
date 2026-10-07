# Phase 4 Manager administration — implementation report

## Authority discovery (recorded before implementation)

Baseline: `75bd8d208699261418e97bbf166547820d0742bf`; working branch `api-phase4-manager-admin`. Desktop and Shared Core remain 1.0.1. No Desktop/core changes are authorized.

| Capability | Released authority | API use |
| --- | --- | --- |
| Identity fields | `model.User`, `model.UserAccount`, `dao.UserDao`, frozen `database/01_create_database.sql` | Purpose-built safe DTO; bounded SQL read projection instead of unbounded `UserService.search` |
| Creation, profile, role, activation, reset | `service.UserService` / `UserServiceImpl`, included in released core classifier | Delegate mutations directly, using `UserAccount.NewUser` and `Changes` |
| Username | `CredentialPolicy.normalizeUsername` / `validateUsername`, database unique constraint | No second username policy |
| Password | `CredentialPolicy.validateNewPassword`, `PasswordHasher` | Released PBKDF2-HMAC-SHA256, 600000 iterations, random salt; core wipes arrays |
| Roles and permissions | `model.Role`, `Permission`, `RolePermissions`, `UserService.roles/permissionMatrix` | Four assignable roles; seeded MANAGER is not assignable; no editable RBAC |
| Authorization | `SecurityContext`, existing `SpringSecurityContext` | Controller and service checks; permission intersection blocks forged authorities |
| Last administrator | `UserDao.lockActiveAdmins` (UPDLOCK/HOLDLOCK), `lockById`, `UserServiceImpl.checkSafety` | Core transaction and lock order; no API approximation |
| Self protection | Core `checkSafety` and `resetPassword` | No self-disable, self-role-change or administrative self-reset |
| First administrator | `UserDao.insertFirst` and Desktop bootstrap | No API bootstrap endpoint |
| Reset/lock state | Core `UserDao.setPassword` | Hash, must-change, password_changed_at; failed attempts zero and locked_until NULL |
| Own password change | Existing Phase 2 `PasswordChangeService` | Reuse existing own-password flow |
| Audit writes | `AuditLogDao` constants and core transactions | API origin supplier; core identity mutation and its audit commit together |
| Audit reads | Frozen `Audit_Log` columns; `ReportDao` audit report | Bounded API projection; omit old/new JSON, description and machine metadata |
| API sessions | Existing `ApiSessionRepository`, `SessionService`, `UserPrincipalLoader` | Existing live active/pwv/fingerprint checks and own-device routes |

Real Users columns include username, password_hash, full_name, phone, email, role_id, is_active, last_login_at, failed_login_attempts, locked_until, created_at, updated_at, must_change_password and password_changed_at. Audit_Log has log_id, user_id, action, table_name, record_id, old_values, new_values, description, machine_name and created_at. There is no authoritative success/failure column. The audit report model is `Reports.AuditRow`, not a separate `model.AuditLog` entity.

## Design decisions before implementation

No required mutation seam is missing in Shared Core 1.0.1. Core owns all identity validation, safety and audit rules. API orchestration must not write Users directly.

Editing uses explicit PUT replacement of the core-editable fields (fullName, phone, email, roleCode, active). Active is required: synthesizing it from a stale read for a partial PATCH could accidentally re-enable a concurrently disabled account. Username is immutable. Creation and reset use an administrator-supplied temporary password with mustChangePassword=true; no password is returned.

Cross-database identity and session changes are separate commits, never a distributed transaction. An API-owned SQL application-lock lease uses the existing Phase 2 per-user resource, held across the core mutation and API revocation. For enable / active PUT, existing sessions are revoked and committed BEFORE enabling; after successful core mutation, sessions are revoked again. For disable/reset, live inactive state or changed credential fingerprint rejects credentials even if subsequent session cleanup fails. A cleanup failure returns a safe 503; the identity change may already have committed. Session-owned locks must be explicitly released before returning a pooled connection. An unsuccessful active PUT may conservatively revoke sessions before core validation rejects the change.

Audit output omits free-form legacy descriptions and JSON entirely; unknown event/category values are represented by OTHER rather than echoed. Lists use Phase 3 paging limits and stable unique tie-breakers, bounded audit dates, parameterized literal search and strict sort allowlists. No schema or dependency change is planned.

Existing own-session/device endpoints remain authoritative; no administrator device-surveillance endpoint is planned. Deployment-level limits for costly admin password operations are an operational recommendation, not a new rate-limiting dependency.

## Verification

Full API `mvn -B -o -f api/pom.xml verify -Dalmahwar.it=true`: **504 tests, 0 failures, 0 errors, 0 skips**. This includes all inherited 346 tests and 158 new Phase 4 tests (117 unit/HTTP, 41 SQL). Desktop `mvn -B -o test`: **373 discovered, 168 executed, 205 opt-in DB skips, 0 failures/errors**. A subsequent targeted Phase 4 SQL run checks stronger refresh-result invalidation, explicit-active PUT/disable ordering, full audit-field secret exclusion and lease release from an independent physical connection: **41 tests passed, 0 failures/errors/skips**, including real HTTP. No production source changed after the complete API run.

Verification uses temporary random non-sa logins with CREATE ANY DATABASE permission, unique disposable catalogs from the frozen schemas, and finally cleanup scoped to the current login's owned catalogs. The full run ended with zero disposable databases and zero temporary verification logins. Master-only AlMahwarDB identity/create-date/state/compatibility metadata was unchanged. No connection to the live business catalog is used for verification.

Package audit: Shared Core `almahwar-store-management-1.0.1-core.jar`, 18 Phase 4 class entries and 35 Phase 3 class entries present. JavaFX, JPA/Hibernate ORM, Flyway and Liquibase libraries absent. Both DB password defaults and JWT signing default empty. No local configuration, .env, scripts, logs or private-key files packaged. Changed-source/evidence secret-pattern scans are clean; the full run explicitly checked the temporary SQL password and server verification credential were absent from evidence, as were raw JWT/refresh tokens. Synthetic test passwords are test-only fixtures and are not packaged.

Evidence is ignored under root target/: phase4-full-api.log, phase4-desktop.log, phase4-full-safety.json, phase4-package-audit.json, phase4-secret-audit.json, and the targeted final SQL log/safety summary. No evidence files are staged or intended for commit. The two earlier Phase 3 verification scripts are absent. No commit, push, merge, tag, PR or rebase occurred.

## Final contract

All paths below require a live unrestricted Phase 2 session. Both controller and service enforce the released permission; no role-name shortcut is used.

| Method and path | Permission | Behavior |
| --- | --- | --- |
| GET `/api/v1/manager/audit` | AUDIT_LOG | Read-only bounded event page |
| GET `/api/v1/manager/admin/users` | USERS_VIEW | Bounded user page |
| GET `/api/v1/manager/admin/users/{id}` | USERS_VIEW | Safe user detail; positive id, 404 if missing |
| POST `/api/v1/manager/admin/users` | USERS_CREATE | Create, 201; username, fullName, phone, email, roleCode, password, confirmPassword |
| PUT `/api/v1/manager/admin/users/{id}` | USERS_EDIT | Replace fullName, phone, email, roleCode, active; username immutable |
| POST `/api/v1/manager/admin/users/{id}/disable` | USERS_EDIT | Core disable and API session cleanup |
| POST `/api/v1/manager/admin/users/{id}/enable` | USERS_EDIT | Revoke existing sessions, core enable, revoke again; fresh login |
| POST `/api/v1/manager/admin/users/{id}/reset-password` | USERS_RESET_PASSWORD | password + confirmPassword; core temporary reset, must-change=true |
| GET `/api/v1/manager/admin/roles` | USERS_VIEW | Released assignable role codes and Arabic names |
| GET `/api/v1/manager/admin/permissions` | USERS_VIEW | Released permission identifier, Arabic description, group and holding roles |

Every user DTO has only id, username, fullName, phone, email, roleCode, roleName, active, mustChangePassword, createdAt and lastLoginAt. Every audit DTO has only id, timestamp, userId, username, fullName, action and category. No entity is serialized directly. There is no permanent-delete, bootstrap, unlock, editable-role or administrator device-list endpoint.

User query: page default 0 (max 10000), size default 20 (max 100), literal q max 100 matching username/fullName, released assignable role, active boolean. Sort name (default), username or created, optional asc/desc, then user id. Core normalizes usernames to trimmed lower-case and validates `[A-Za-z0-9._-]{3,50}`; profile validation, phone normalization and email rules remain core-owned. Passwords are 8..128 characters with a letter and digit, differ from username case-insensitively, and require confirmation, exactly as released. No generated-password delivery system is introduced.

Audit query: Phase 3 date presets or explicit inclusive from/to, maximum 366 days; default today on SQL Server's local business clock. Same paging limits. Fixed sort `date,desc` (timestamp then id descending); no free-text q. Optional positive userId, released AuditLogDao event constants plus existing API_LOGOUT_ALL/API_SESSION_REVOKED/API_REFRESH_REUSE, and category from Users, Products, Customers, Suppliers, Sales, Purchases, Expenses, Cash_Transactions, Sale_Returns, Purchase_Returns, Quotations, Company_Settings, System_Settings. Unknown historical values return OTHER and are not additional filter values. No success/failure filter is invented. Count/page can observe concurrent changes under normal released isolation; production measurements should evaluate broad filters and deep pages using existing indexes, without schema changes.

Unknown mutation properties follow the existing mapper behavior and cannot bind to core entities. Password-bearing DTO toString methods redact their contents. Password strings share the existing JSON-string memory limitation; converted char arrays are wiped both by core and in API finally blocks. No bodies or exception text are logged by the new code. Core field validation maps to safe fixed Arabic messages and request IDs. All successful admin/audit responses are no-store; inherited security/error handling remains in place. No new rate-limit platform or dependency is introduced; reverse-proxy limits for admin create/reset are recommended for deployment.

## Concurrency and operational limits

Core locks active administrators first, then the target user, and checks safety within the same transaction as its writes/audit. Concurrent cross-disable/cross-demote requests preserve one active administrator. The API SQL lease uses the same database/principal/resource as Phase 2 session mutations, not a Java synchronized lock. Multiple API instances therefore serialize session creation/rotation and administration for the same target. Session revocation uses the held connection rather than recursively calling Phase 2 revokeAll on another connection.

Full PUT deliberately expresses active state, including an explicit re-enable if true. Update-versus-disable follows the last committed core lifecycle action; there is no invented optimistic concurrency contract. Requests authorized before a concurrent change may finish. Future requests use live state. Post-commit cleanup failures are operational 503s; clients must re-read state rather than assume rollback. Reset fingerprints cover same-second password changes. Enable's pre-revocation is committed independently, so a post-commit outage cannot restore old credentials. Revoked/expired sessions are never unrevoked. Core behavior and the separate API credential checks jointly enforce these guarantees.

The existing `/api/v1/auth/sessions`, DELETE `/api/v1/auth/sessions/{sid}`, `/api/v1/auth/logout` and `/api/v1/auth/logout-all` cover my devices/my sessions. Existing validated user-supplied deviceLabel remains metadata. There is no raw-token/fingerprint/IP browsing or new admin session endpoint. Internal session revocation is sufficient for disable/reset.

## Intended change set

New production files, all under `api/src/main/java/com/almahwar/api/admin/`:

- AdminAccess.java
- AdminController.java
- AdminCoreConfiguration.java
- AdminDtos.java
- AdminReadRepository.java
- AdminService.java
- AdminSessionRepository.java
- AdminValidationAdvice.java
- AuditController.java
- AuditQueryService.java
- AuditReadRepository.java

Other intended files:

- `api/src/main/java/com/almahwar/api/manager/ManagerCacheAdvice.java`: reuse no-store advice for new controllers.
- `api/src/main/java/com/almahwar/api/manager/ManagerErrorAdvice.java`: reuse safe connection-failure mapping for new controllers.
- `api/src/test/java/com/almahwar/api/AdminApiTest.java`: new transport/security tests.
- `api/src/test/java/com/almahwar/api/AdminSqlServerIntegrationTest.java`: released-core SQL, races, cross-DB failure and real HTTP tests.
- `api/src/test/java/com/almahwar/api/admin/AdminSessionRepositoryTest.java`: lease ordering/error/abort tests.
- `api/src/test/java/com/almahwar/api/ManagerSqlServerIntegrationTest.java`: identify the original 19 Phase 3 business routes separately from new admin/audit paths in the existing OpenAPI assertion; all 19 remain checked.
- `docs/API_ARCHITECTURE.md`.
- `docs/API_PHASE4_MANAGER_ADMIN_REPORT.md`.

Nineteen intended files. The existing untracked `docker` file is excluded. No target files, logs or verification scripts belong to the change set. Protected Desktop/core source, root pom, database scripts, config and release files are untouched. Historical `.gitignore`/README differences from main predate Phase 4.

## Numbered review report

1. Status: READY FOR REVIEW. No CORE GAP was found or bypassed.
2. Branch: api-phase4-manager-admin.
3. Starting/current HEAD: 75bd8d208699261418e97bbf166547820d0742bf.
4. Phase 3 local, origin tracking and actual remote: 75bd8d208699261418e97bbf166547820d0742bf.
5. main/origin/main and actual remote: c0234e49e2bf00e094f2bf5de68bebd7fe5f963b.
6. Phase 2 local/origin/actual remote: 32c673406025c6c78f9adae2c61e604c954c1604.
7. Core adoption local/origin/actual remote: 0aa9f7b9239f6c9a060ba00c8f0309993a9deb99. api-development: 5cd2819eb2495123c1a41b5db324c267f389d5ee. v1.0.0/v1.0.1 peeled SHAs remain c27b2e36c2f05596c86cc07d13f59b82122c3945 / c0234e49e2bf00e094f2bf5de68bebd7fe5f963b.
8. Desktop version: unchanged 1.0.1.
9. Shared Core: released 1.0.1 classifier, reused directly.
10. Business schema: frozen 1.10.0; verified on disposable schema, no live-catalog query.
11. API schema: frozen 1.0.0; verified on disposable schema.
12. Authority map: documented before source implementation, above.
13. Core services: UserServiceImpl with UserDao, RoleDao, AuditLogDao(API), SpringSecurityContext; released RolePermissions, CredentialPolicy and PasswordHasher.
14. CORE GAP: none required; no Desktop/shared-core 1.0.2 change needed.
15. Audit endpoint: GET /api/v1/manager/audit.
16. Audit permission: AUDIT_LOG, ADMIN only in released matrix.
17. Audit filters: bounded dates, positive userId, released action and allowlisted category; no invented success/failure.
18. Audit paging/sort: default 20, max 100, page max 10000; timestamp/id descending; max 366 inclusive days.
19. Audit DTO: id, timestamp, userId, username, fullName, action, category; unknown events/categories OTHER; no blobs/descriptions/record identifiers/machine metadata.
20. User list: GET /api/v1/manager/admin/users, bounded.
21. User detail: GET /api/v1/manager/admin/users/{id}, safe 404 for absent id.
22. Creation: POST users, implemented through core; 201, temporary supplied password, initially active, must-change=true.
23. Edit: PUT users/{id}, full explicit editable profile; immutable username, no mass assignment.
24. Disable: POST users/{id}/disable, core safety/audit and session cleanup.
25. Enable: POST users/{id}/enable, committed pre-revocation and fresh login.
26. Reset: POST users/{id}/reset-password, core rules, no credential response.
27. Roles: GET /roles under admin prefix; four core assignable roles and Arabic names.
28. Permissions: GET /permissions under admin prefix; core identifiers/descriptions/groups/role sets.
29. Permanent deletion: absent.
30. Authorization model: released USERS_* / AUDIT_LOG permissions, no API role-name grants.
31. Controllers: explicit @PreAuthorize checks, tested across every route and forged Spring authorities.
32. Services: repeated core permission checks; direct forged-principal calls denied before data access.
33. User DTO: id, username, fullName, phone, email, roleCode, roleName, active, mustChangePassword, createdAt, lastLoginAt.
34. Exclusions: hashes/salts/password versions/fingerprints, raw passwords, failed/lock internals, JWT/refresh credentials.
35. Username: released normalization, validation and uniqueness; immutable on edit.
36. Password policy: delegated to CredentialPolicy, including confirmation.
37. Hashing: released PBKDF2-HMAC-SHA256, 600000 iterations, random salt; arrays wiped.
38. Must-change: core-supported true for create/reset; restricted login requires own password change and returns no refresh credential.
39. Lock/failure: reset clears failures/lock only through core setPassword behavior.
40. Last administrator: core locks and checks; self-disable/self-role-change denied, normal self-profile edit allowed.
41. Last-admin concurrency: concurrent cross-disable and cross-demote preserve exactly one active ADMIN; one request succeeds, one is rejected.
42. Disable: subsequent access/refresh rejected, login denied; live inactive state closes cleanup-failure gap.
43. Enable: old access/refresh credentials stay invalid; new login has a new session.
44. Reset: old credentials invalid, including equal stored-second timestamps through fingerprint validation; separate cleanup failure remains fail-closed.
45. Role change: next request uses current core permissions even when a direct core change leaves the existing API session unrevoked; API edits additionally revoke sessions conservatively.
46. DB ownership: business identity remains solely in business DB; API DB stores sessions/refresh families only.
47. Transactions: separate commits, no distributed transaction; safe 503 may follow committed identity change.
48. Audit writes: released USER_CREATED/UPDATED/DISABLED/ENABLED/ROLE_CHANGED/PASSWORD_RESET; same core identity transaction.
49. Audit secrecy: new tests check passwords, hashes, raw access/refresh credentials and runtime secrets; legacy free-form payloads excluded from read DTO.
50. My devices: existing auth/session routes reused; no duplicated Manager contract.
51. Admin session revocation: internal only, sufficient for disable/reset; no surveillance endpoint.
52. User paging: bounded username/fullName literal search, assignable role/active filters; name/username/created sort and unique id.
53. SQL safety: parameterized values, shared LIKE escaping, strict allowlists; no arbitrary SQL or SELECT * in new repositories.
54. Cache: reused Manager no-store advice for new controllers.
55. Errors: established ApiError/request IDs, fixed Arabic field messages, safe 400/403/404/503; no exception/driver text.
56. Phase 4 unit/HTTP: 117 passed (111 AdminApiTest + 6 lease tests), no failures/errors/skips.
57. Phase 4 SQL: 41 passed in full suite; final strengthened 41-test rerun also passed with zero failures/errors/skips.
58. Phase 4 concurrency: eight parameterized SQL race cases plus same-second, outage and independent-lease checks.
59. Real HTTP: random-port server over disposable catalogs; create/edit/disable/enable/reset, denials, session invalidation, list/audit/metadata, no-store, safe errors and OpenAPI checked.
60. Phase 3: inherited 164 unit/HTTP + 32 SQL green; all 19 original business routes retained, accounting unchanged.
61. Phase 2: inherited auth/session regression green, including 33 SQL session tests, rotation/reuse/logout/password/fingerprint/outage/concurrency behavior.
62. Phase 1: inherited product HTTP/sorting/core authorization and SQL tests green.
63. Desktop: 373 discovered, 168 executed, 205 opt-in skips, 0 failures/errors.
64. Complete API: 504 tests, 0 failures, 0 errors, 0 skips (346 inherited + 158 Phase 4).
65. Secret scan: changed files and evidence clean for real-secret patterns and runtime verification credentials.
66. Plaintext/token scan: no raw JWT/refresh credentials in verification evidence; synthetic fixture credentials remain test-only.
67. Package: executable API JAR built, Shared Core 1.0.1 and both Manager phases present; empty credential/JWT defaults.
68. JavaFX/JPA: absent; no ORM, Flyway or Liquibase added.
69. Disposable databases: zero after both full and final targeted runs.
70. Temporary SQL logins: zero after both full and final targeted runs.
71. AlMahwarDB safety: no live-catalog test connection or mutation; master-only identity/state metadata unchanged.
72. Desktop protected files: no Phase 4 changes, including root pom, src, database, config and release resources.
73. Schemas: no schema/migration/index/trigger/column edits.
74. Dependencies: zero changes.
75. Write scope: only approved user/security administration and inherited auth/session/audit writes; no financial/business mutations.
76. User deletion: no DELETE users route; DELETE remains own-session revocation only.
77. Flutter: none.
78. Phase 5: not started.
79. SQL 1205 document-numbering limitation: unchanged, no fix attempted.
80. Change set: nineteen intended files listed above; docker/target/logs/secrets excluded.
81. Git status: all intended changes unstaged/uncommitted; docker remains the original unrelated untracked item. Exact final status below.
82. Commit: none; HEAD unchanged.
83. Push: none; protected remote refs unchanged, no Phase 4 remote created.
84. Merge/tag/rebase/PR: none.

Final `git status --short`:

```text
 M api/src/main/java/com/almahwar/api/manager/ManagerCacheAdvice.java
 M api/src/main/java/com/almahwar/api/manager/ManagerErrorAdvice.java
 M api/src/test/java/com/almahwar/api/ManagerSqlServerIntegrationTest.java
 M docs/API_ARCHITECTURE.md
?? api/src/main/java/com/almahwar/api/admin/
?? api/src/test/java/com/almahwar/api/AdminApiTest.java
?? api/src/test/java/com/almahwar/api/AdminSqlServerIntegrationTest.java
?? api/src/test/java/com/almahwar/api/admin/
?? docker
?? docs/API_PHASE4_MANAGER_ADMIN_REPORT.md
```

`git diff --check` passes. The index is empty. Docker SHA256 remains `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`. Protected refs and peeled release tags match the accepted baseline. Final package and secret checks pass. Cross-database operational limits remain as documented; no distributed atomicity or optimistic edit guarantee is claimed.

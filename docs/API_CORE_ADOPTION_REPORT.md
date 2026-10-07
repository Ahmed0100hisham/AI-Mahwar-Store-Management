# API shared core adoption — verification report

Date: 2026-10-07 (Africa/Cairo). Implementation and verification only; no commit, tag or push.

1. **Final status:** implementation complete and verified for review. No adoption blocker remains. The known numbering deadlock blocks future high-concurrency document creation, which is outside this phase.
2. **Branch:** `api-core-adoption`.
3. **Starting API commit:** `5cd2819eb2495123c1a41b5db324c267f389d5ee` (`api-development`), unchanged.
4. **Desktop main commit:** `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b`, unchanged.
5. **v1.0.1 target:** peeled commit `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b`. Annotated tag object `8b97ebb60e333522f784e63935cb2ad2b088c627` is unchanged. v1.0.0 peeled target remains `c27b2e36c2f05596c86cc07d13f59b82122c3945` (annotated object `7202640ad2f44ef879920cff2d2b08ab13fb6446`).
6. **Schema:** exactly 1.10.0. Schema SQL files match released main byte-for-byte in Git.
7. **Exact Git strategy:** inspected all refs and ancestry first. Common ancestor was v1.0.0 (`c27b2e3`). Ran `git switch -c api-core-adoption api-development`, then `git merge --no-commit --no-ff main`. The merge is deliberately pending: HEAD is still the API commit; MERGE_HEAD is the official Desktop main commit. Both lineages are prepared for a future authorized merge commit. No rebase, history rewrite, tag move or main merge occurred.
8. **Conflicts:** none. README merged automatically; release version edits and the API introduction coexist. Staged Desktop/POM/test files are exact imports from released main, not new Desktop modifications.
9. **Core coordinates:** `com.almahwar:almahwar-store-management:jar:core:1.0.1` (`groupId`, `artifactId`, type, classifier, version).
10. **Consumption:** ordinary compile/runtime Maven dependency in the independent API POM. The unchanged root Maven build installs the official attached classifier and POM locally. API explicitly excludes javafx-controls and javafx-fxml; no absolute target path, checked-in generated JAR or reactor rewrite.
11. **Developer build steps:** Java 17 and Maven; from root run `mvn install`, then `mvn -f api/pom.xml verify`. For real SQL tests set `ALMAHWAR_IT_DB_HOST`, `_PORT`, `_USER`, `_PASSWORD`, `_TRUST_SERVER_CERTIFICATE`, then run `mvn -f api/pom.xml verify -Dalmahwar.it=true`. The test login needs permission to create its disposable databases; it is not a production API account. Test paths assume execution from `api/` (use `cd api; mvn verify -Dalmahwar.it=true` for SQL integration). For Desktop golden tests run from root with temporary `db.name`, `-Dgolden=true -Ddb.it=true -Dtest=GoldenBehaviorTest,ProviderGoldenTest`. Keep credentials in process environment or ignored local configuration, never command history/source.
12. **Connection adapter:** DataSourceConnectionProvider borrows directly from Hikari DataSource and preserves Phase 1 safe 503 on acquisition failure. CoreConnectionBinding installs it before product persistence is used; an owning context restores its previous provider on close. Production assumes one API application per JVM, matching the released process-wide seam.
13. **Security adapter:** SpringSecurityContext reads the authenticated request principal each time, maps identity/role to User/UserSession, intersects principal grants with the released role matrix, and strips every permission when password change is required. No mutable user is cached and no password/hash or invented session timestamp is mapped. Disabled/deleted users are rejected by the unchanged live principal loader before mapping.
14. **Transaction ownership:** removed the product service's Spring transaction wrapper. Core TransactionManager owns begin/commit/rollback/close exclusively. Mock lifecycle/order tests and real Hikari/SQL Server tests prove success commit, SQL/runtime failure rollback, connection return and auto-commit reset. No Spring transaction is active around the tested core call. Count/page are separate short reads; no snapshot consistency is promised.
15. **Duplicate inventory:** password hashing; permissions/role grants; product authorization/inactive/cost rules; LIKE escaping/JDBC primitives; schema version constant; unknown-user tracker; auth/user DAO SQL and orchestration; authentication audit; product paging projection; health/schema probing. No duplicate CredentialPolicy/password-change implementation exists. Full A/B/C rationale is in API_ARCHITECTURE.md.
16. **Removed/replaced:** API PasswordHasher, Permission, RolePermissions; copied product visibility policy; copied LIKE escape implementation. Schema checker now uses the core required-version constant.
17. **Retained/deferred:** bounded unknown-user throttle (API memory protection absent from core); JWT/Spring/HTTP infrastructure; bounded paging/sort projection because released core is unpaged; API readiness probing. AuthUserRepository/AuthService persistence and audit overlap are explicitly deferred to preserve Phase 1 row-lock/DB-clock/auth-failure/client-address behavior while the Desktop AuthServiceImpl/SessionManager are excluded. No HTTP/JWT concerns moved into core.
18. **Product POC:** ProductQueryService invokes actual ProductServiceImpl.search through a per-call PagedProductDao. The core supplies the authorized filter and hides cost; ProductRepository uses BaseDao and ConnectionSource for bounded SQL. It does not load the whole catalog. DTO mapping remains API-owned.
19. **HTTP contract:** no public change. Same paths, DTO shape, decimal strings, pagination limits, sort allowlist, inactive restrictions, authentication and safe error codes. Unauthorized cost is omitted both from SQL selection and response. Core access-denied/database exceptions are mapped to existing generic 403/500 responses; pool acquisition remains 503.
20. **Authentication regression:** valid login; wrong/unknown credentials; inactive/locked users; lockout and audit; older-hash upgrade; PBKDF2-HMAC-SHA256 at 600,000 iterations; Arabic/symbol passwords; pwv revocation; JWT expiry, issuer/audience/algorithm/config validation all pass. Independent fixed KDF vectors and real core-created database users verify stored-format compatibility. JWT lifetime default remains 15 minutes. Phase 1 configuration precedence is unchanged.
21. **Authorization parity:** ADMIN, ACCOUNTANT, CASHIER, STOREKEEPER use the released matrix. Grants match frozen Phase 1 fixture. All four product roles tested; unknown roles have no grants. Core denies access even with Spring proxy checks bypassed; request threads do not share principals. Endpoint checks remain defense in depth.
22. **must_change_password:** no core permissions; product access rejected; existing Phase 1 filter response and /auth/me behavior preserved. Password must still be changed on Desktop; no password-change endpoint added. Disabled/deleted users and stale pwv are rejected immediately on subsequent requests.
23. **Hikari verification:** real HikariProxyConnection from the configured API pool, correct temporary catalog, auto-commit true on borrow and reset after core transactions, zero active connections after completed operations; rollback leaves zero inserted test rows and success is committed.
24. **Temporary DB result:** ten SQL Server tests pass on a unique AlMahwarApiIT_* database constructed from the existing 1.10.0 script. Desktop golden tests use AlMahwarProviderAdoptionIT. All adoption test databases and temporary SQL logins were removed; master-catalog cleanup check returned zero of each. Test administration used the local development container; API test connections used a temporary non-sa login.
25. **Unit / no-DB totals:** final API verify: 99 discovered, 89 executed/passed, ten SQL opt-ins skipped, zero failures/errors. Root Desktop install: 373 discovered, 168 executed/passed, 205 opt-ins skipped, zero failures/errors. Includes released CoreBoundaryTest and ConnectionSeamTest.
26. **Integration totals:** ten API SQL Server tests passed with none skipped; two Desktop golden tests passed (17 scenarios each, default and DataSource paths), byte-identical to the frozen v1.0.0 golden reference. The full SQL-enabled API run passed 98/98 before the final additional core-error HTTP test; that added test passed in the final 89-test no-DB run. Thus 99 unique current API tests are validated across the two runs. The broader skipped Desktop integration/concurrency suites were not rerun; no Desktop behavior/source changed.
27. **API startup:** independent API package build succeeds; real embedded HTTP server starts on a random port without JavaFX. Startup rejects an incompatible temporary schema without repairing/migrating it.
28. **Health/readiness:** real HTTP liveness/readiness return 200 on compatible temporary SQL Server; readiness returns 503 during the isolated mismatch test and recovers after the test restores 1.10.0.
29. **Products endpoint:** real HTTP authenticated product paging returns 200 without cashier cost; real SQL and MockMvc suites cover all four roles, inactive behavior, paging/sort/search, denied/unauthenticated access and safe errors.
30. **Core boundary/dependency:** released boundary tests pass; actual runtime classifier contents verified. Runtime graph records core 1.0.1, Hikari 7.0.2, SQL Server JDBC 13.4.0.jre11 (existing Spring Boot management), POI 5.2.5 and its support libraries. No explicit dependency upgrade. jdeps finds no JavaFX, controller, Spring or API reference in the core.
31. **JavaFX/UI:** no JavaFX runtime dependency or classes; no Desktop controllers/FXML/CSS in core or API runtime. Approved minimal AppConfig/DatabaseConnection classes remain in core. SessionManager, AuthServiceImpl, backup/health/status implementations and the three Desktop admin DAOs are excluded as defined by released POM/tests. Core report-export POI linkage remains documented.
32. **Secret scan:** 365 source/document candidates plus production defaults checked for the actual local container credential, private keys, common token patterns and nonempty production password/JWT defaults; none found. New password vectors are explicitly test-only. No production resource credentials were added; no secret value appears in this report. Pattern scan is scoped verification, not a claim to universal secret detection.
33. **Schema confirmation:** shared SettingsService constant is 1.10.0, API checker consumes it, temporary DB final version is 1.10.0, and database/ is identical to main. No migration/new business table/index/procedure was introduced.
34. **AlMahwarDB safety:** no wipe, restore, recreation, migration, schema update or business-data mutation against AlMahwarDB. Adoption SQL mutations were confined to disposable test databases. No live business login was needed. Production HTTPS, intentional CORS, safe errors, correlation logging and non-sa account guidance remain unchanged.
35. **Desktop integrity:** main, origin/main, v1.0.0, v1.0.1 and api-development refs unchanged. Root POM/src/database/config/.github match official main. Desktop Main-Class remains com.almahwar.Launcher and official app/core version remains 1.0.1. Released dist artifacts were not modified.
36. **API Phase 2:** NOT started. No AlMahwarApiDB, refresh/session tables, refresh tokens, logout/logout-all, password-change endpoint, sid claims, rotation or reuse detection.
37. **Flutter:** NOT started. Planned Al Mahwar Manager is not a mobile POS; no mobile sales creation is planned.
38. **Known deadlock:** unchanged/documented. SELECT MAX(...) WITH (UPDLOCK, HOLDLOCK) may produce SQL Server 1205 under concurrent document creation, with complete transaction rollback. A separately approved design remains required before high-concurrency document mutation endpoints. Read-only adoption does not enable those endpoints.
39. **Git status:** pending uncommitted merge on api-core-adoption; official Desktop import staged, adoption edits/new files unstaged/untracked; no unmerged conflicts. Full snapshot below. docker remains untracked and its unchanged empty-file SHA-256 is E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855.
40. **Changed-file summary:** full inventory below separates adoption edits from unchanged official release imports. Generated logs/JARs, local Maven installs and disposable verification scripts are ignored build outputs.
41. **No commit:** confirmed. HEAD remains 5cd2819eb2495123c1a41b5db324c267f389d5ee; merge deliberately not committed.
42. **No tag:** no tag created, moved or deleted; both annotated tag objects and peeled targets unchanged.
43. **No push:** confirmed; no push command executed, remote-tracking main unchanged. No merge into main.

## Build and test evidence

Paths below are ignored local verification artifacts:

- Root target/core-adoption-build.log — Desktop install/build, 168 passing non-DB tests.
- API target/core-adoption-full.log — SQL-enabled API verify, 98 passing tests including ten SQL integration tests.
- API target/core-adoption-final-unit.log — final API package/verify, 89 passing non-DB tests and ten intentional SQL skips.
- API target/core-adoption-runtime-final.log — actual classifier/runtime boundary recheck, two passing tests.
- Root target/core-adoption-golden.log — default/provider golden tests, two passing tests, all 17 scenarios per path.
- API target/core-adoption-runtime-dependencies.txt — resolved runtime tree.
- Root target/core-adoption-jdeps.txt — core class dependency inspection.

## Changed files

The following inventory is relative to the repository root. Git's configured CRLF handling is respected; ordinary
`git diff --check` passes. The pending merge means staged Desktop imports are compared with the starting API commit;
`git diff main -- pom.xml src database config .github` is empty.

### Adoption edits relative to the merged index

```text
M	api/README.md
M	api/pom.xml
M	api/src/main/java/com/almahwar/api/auth/AuthService.java
M	api/src/main/java/com/almahwar/api/auth/dto/CurrentUserResponse.java
M	api/src/main/java/com/almahwar/api/error/GlobalExceptionHandler.java
M	api/src/main/java/com/almahwar/api/health/SchemaCompatibilityChecker.java
M	api/src/main/java/com/almahwar/api/product/ProductQueryService.java
M	api/src/main/java/com/almahwar/api/product/ProductRepository.java
M	api/src/main/java/com/almahwar/api/product/ProductResponse.java
M	api/src/main/java/com/almahwar/api/security/ApiUser.java
M	api/src/main/java/com/almahwar/api/security/CurrentUser.java
D	api/src/main/java/com/almahwar/api/security/PasswordHasher.java
D	api/src/main/java/com/almahwar/api/security/Permission.java
D	api/src/main/java/com/almahwar/api/security/RolePermissions.java
M	api/src/main/java/com/almahwar/api/security/UserPrincipalLoader.java
M	api/src/test/java/com/almahwar/api/AuthenticationApiTest.java
M	api/src/test/java/com/almahwar/api/DatabaseUnavailableTest.java
M	api/src/test/java/com/almahwar/api/ProductApiTest.java
M	api/src/test/java/com/almahwar/api/SqlServerIntegrationTest.java
M	api/src/test/java/com/almahwar/api/product/ProductSortTest.java
M	api/src/test/java/com/almahwar/api/security/PasswordCompatibilityTest.java
M	api/src/test/java/com/almahwar/api/security/PermissionMatrixParityTest.java
M	api/src/test/java/com/almahwar/api/support/ApiWebTestBase.java
M	api/src/test/java/com/almahwar/api/support/TemporaryDatabase.java
M	docs/API_ARCHITECTURE.md
M	docs/adr/ADR-001-business-core-and-api-sessions.md
```

### New adoption files

```text
api/src/main/java/com/almahwar/api/core/CoreConnectionBinding.java
api/src/main/java/com/almahwar/api/core/DataSourceConnectionProvider.java
api/src/main/java/com/almahwar/api/core/SpringSecurityContext.java
api/src/main/java/com/almahwar/api/product/PagedProductDao.java
api/src/test/java/com/almahwar/api/core/CoreAdaptersTest.java
api/src/test/java/com/almahwar/api/core/SharedCoreRuntimeTest.java
api/src/test/java/com/almahwar/api/product/ProductCoreAuthorizationTest.java
api/src/test/resources/phase1-passwords.properties
api/src/test/resources/phase1-permissions.properties
docs/API_CORE_ADOPTION_REPORT.md
```

### Official release imports staged by the pending merge

```text
M	README.md
A	RELEASE_NOTES_1.0.1.md
A	docs/adr/ADR-001-business-core-and-api-sessions.md
M	pom.xml
M	src/main/java/com/almahwar/dao/AuditLogDao.java
M	src/main/java/com/almahwar/dao/BaseDao.java
A	src/main/java/com/almahwar/dao/ConnectionProvider.java
A	src/main/java/com/almahwar/dao/ConnectionSource.java
M	src/main/java/com/almahwar/dao/TransactionManager.java
A	src/test/java/com/almahwar/CoreBoundaryTest.java
A	src/test/java/com/almahwar/dao/ConnectionSeamTest.java
A	src/test/java/com/almahwar/service/ConcurrencyRegressionTest.java
A	src/test/java/com/almahwar/service/GoldenBehaviorTest.java
A	src/test/java/com/almahwar/service/GoldenDatabase.java
A	src/test/java/com/almahwar/service/GoldenScenarios.java
A	src/test/java/com/almahwar/service/ProviderGoldenTest.java
A	src/test/resources/golden/desktop-1.0.0-golden.txt
```

### Final git status snapshot

```text
M  README.md
A  RELEASE_NOTES_1.0.1.md
 M api/README.md
 M api/pom.xml
 M api/src/main/java/com/almahwar/api/auth/AuthService.java
 M api/src/main/java/com/almahwar/api/auth/dto/CurrentUserResponse.java
 M api/src/main/java/com/almahwar/api/error/GlobalExceptionHandler.java
 M api/src/main/java/com/almahwar/api/health/SchemaCompatibilityChecker.java
 M api/src/main/java/com/almahwar/api/product/ProductQueryService.java
 M api/src/main/java/com/almahwar/api/product/ProductRepository.java
 M api/src/main/java/com/almahwar/api/product/ProductResponse.java
 M api/src/main/java/com/almahwar/api/security/ApiUser.java
 M api/src/main/java/com/almahwar/api/security/CurrentUser.java
 D api/src/main/java/com/almahwar/api/security/PasswordHasher.java
 D api/src/main/java/com/almahwar/api/security/Permission.java
 D api/src/main/java/com/almahwar/api/security/RolePermissions.java
 M api/src/main/java/com/almahwar/api/security/UserPrincipalLoader.java
 M api/src/test/java/com/almahwar/api/AuthenticationApiTest.java
 M api/src/test/java/com/almahwar/api/DatabaseUnavailableTest.java
 M api/src/test/java/com/almahwar/api/ProductApiTest.java
 M api/src/test/java/com/almahwar/api/SqlServerIntegrationTest.java
 M api/src/test/java/com/almahwar/api/product/ProductSortTest.java
 M api/src/test/java/com/almahwar/api/security/PasswordCompatibilityTest.java
 M api/src/test/java/com/almahwar/api/security/PermissionMatrixParityTest.java
 M api/src/test/java/com/almahwar/api/support/ApiWebTestBase.java
 M api/src/test/java/com/almahwar/api/support/TemporaryDatabase.java
 M docs/API_ARCHITECTURE.md
AM docs/adr/ADR-001-business-core-and-api-sessions.md
M  pom.xml
M  src/main/java/com/almahwar/dao/AuditLogDao.java
M  src/main/java/com/almahwar/dao/BaseDao.java
A  src/main/java/com/almahwar/dao/ConnectionProvider.java
A  src/main/java/com/almahwar/dao/ConnectionSource.java
M  src/main/java/com/almahwar/dao/TransactionManager.java
A  src/test/java/com/almahwar/CoreBoundaryTest.java
A  src/test/java/com/almahwar/dao/ConnectionSeamTest.java
A  src/test/java/com/almahwar/service/ConcurrencyRegressionTest.java
A  src/test/java/com/almahwar/service/GoldenBehaviorTest.java
A  src/test/java/com/almahwar/service/GoldenDatabase.java
A  src/test/java/com/almahwar/service/GoldenScenarios.java
A  src/test/java/com/almahwar/service/ProviderGoldenTest.java
A  src/test/resources/golden/desktop-1.0.0-golden.txt
?? api/src/main/java/com/almahwar/api/core/CoreConnectionBinding.java
?? api/src/main/java/com/almahwar/api/core/DataSourceConnectionProvider.java
?? api/src/main/java/com/almahwar/api/core/SpringSecurityContext.java
?? api/src/main/java/com/almahwar/api/product/PagedProductDao.java
?? api/src/test/java/com/almahwar/api/core/CoreAdaptersTest.java
?? api/src/test/java/com/almahwar/api/core/SharedCoreRuntimeTest.java
?? api/src/test/java/com/almahwar/api/product/ProductCoreAuthorizationTest.java
?? api/src/test/resources/phase1-passwords.properties
?? api/src/test/resources/phase1-permissions.properties
?? docker
?? docs/API_CORE_ADOPTION_REPORT.md
```

API SHARED CORE ADOPTION READY FOR REVIEW

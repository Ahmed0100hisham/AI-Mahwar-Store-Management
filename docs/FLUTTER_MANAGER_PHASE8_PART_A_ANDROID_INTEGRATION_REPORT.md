# Part A recovery notice — 2026-10-10

This report was recovered after its working copy was found empty (0 bytes). The saved historical checkpoint below is preserved verbatim, including its earlier NOT VERIFIED states. The later completion reconciliation after it records independently inspected native UI, host HTTP and server-state evidence. Read both checkpoints; the earlier limitations are not silently replaced.

This recovery restores documentation only. All native/live results below are historical evidence, not newly executed during recovery. Current fresh verification is recorded separately in FLUTTER_MANAGER_PHASE8_PART_B_SECURITY_REVIEW.md.

## Begin preserved historical Part A checkpoint


# Flutter Manager Phase 8 — Part A: real Android integration

Date: 2026-10-10. **READY FOR HUMAN REVIEW AND PART B**, with the native cases and release limitations below explicitly open.

The actual Flutter Android debug application authenticated against the accepted Spring Boot API and Shared Core using disposable SQL Server business and security databases. ADMIN, ACCOUNTANT, CASHIER and STOREKEEPER logins succeeded. Native refresh-token persistence, restoration, rotation, logout and externally revoked-session cleanup were exercised. No production implementation changes were required.

No staging, commit, push, merge, rebase, tag, PR, production release build, publishing or distribution was performed.

## 1. Baseline and exact changes

The entry gate verified local `flutter-manager-phase7-release-readiness`, its origin tracking ref and the actual remote branch at **`911464e19c01c43555d115eef947f155d4bd7dc0`**, with ahead/behind **0/0**, an empty index and only `?? docker`. The same Phase 7 equality was checked again at completion.

The local branch **`flutter-manager-phase8-android-integration`** was created from that exact commit. Current HEAD remains **`911464e19c01c43555d115eef947f155d4bd7dc0`**. Phase 8 has no new commit or remote branch.

Exact reviewable manifest: **0 modified tracked files + 1 new documentation file**:

| Change | Path |
| --- | --- |
| New | docs/FLUTTER_MANAGER_PHASE8_PART_A_ANDROID_INTEGRATION_REPORT.md |

All **613 baseline tracked files** retain their raw SHA-256 hashes, including Flutter source/tests, Android production/debug configuration, dependencies, Desktop, API, Shared Core, SQL, authentication and frozen contracts. The accepted Phase 7 reports were preserved. `docker` is unrelated, unchanged and excluded from this manifest.

Temporary verification Java/PowerShell helpers, sanitized evidence, an instrumentation helper APK and extracted accepted JAR runtime files are ignored under `target/flutter-phase8/`. The actual debug APK/build outputs remain ignored under `mobile/manager_app/build/`. They are not source changes or intended commit files. No helper, log, screenshot, credential, APK or build output was staged.

## 2. Discovery and environment

Discovery read the Phase 7 readiness report, frozen `API_MANAGER_CONTRACT_V1.md`, Phase 2 authentication documentation, Flutter Phase 1 architecture, actual API configuration, existing temporary SQL fixtures, Android manifests, `AppConfig`, authentication transport/controller and `SecureTokenVault`.

| Item | Freshly observed environment/result |
| --- | --- |
| Flutter / Dart | Flutter 3.47.5 stable / Dart 3.13.4 |
| Host | Windows 10 Pro 22H2, Arabic locale |
| Doctor / devices | Both commands executed; exit 0. Doctor still reports unaccepted Android licenses and missing Windows C++ components. No Android device was running at entry. |
| Android SDK | SDK/build-tools 36.0.0; installed platform android-37.0; emulator 37.1.11.0 |
| Build Java / API Java | Android Studio JBR 25.0.3 / existing Temurin 17.0.19 |
| Emulator | Existing Pixel_7 AVD, Android 17/API 37.2, serial emulator-5558; owned read-only instance, headless, no snapshot save |
| SQL Server | Existing `almahwar-sql` container, SQL Server 2022, host port 14333; no container created, restarted, reconfigured or stopped |
| App identity | com.almahwar.manager_app; Arabic label مساحة الإدارة; existing version 0.1.0+1 |

The Android debug build recorded a Kotlin daemon connection failure and succeeded through fallback compilation. No Gradle, JDK, dependency or platform configuration was changed. Repeated emulator UWB service aborts remain a platform limitation. Inspected application logs did not identify an app fatal crash; this does not certify the emulator platform or physical devices.

## 3. Real API runtime and private configuration

The unchanged accepted executable was `api/target/almahwar-api-0.1.0-SNAPSHOT.jar`, SHA-256 **`0FF08808271DBBF69588E980CD680BD3CFB337970293F79021E7FCFF211A937C`**. Its packaged runtime contains `BOOT-INF/lib/almahwar-store-management-1.0.1-core.jar`. Verification helpers compiled separately against existing test support and this extracted runtime; the API/Core implementation was not rebuilt or replaced.

Actual released configuration names, with private values omitted:

| Configuration | Verification value/purpose |
| --- | --- |
| SPRING_PROFILES_ACTIVE | dev, confined to the disposable instance |
| SERVER_ADDRESS / ALMAHWAR_API_PORT | 127.0.0.1 / 49399; actual loopback listener verified |
| ALMAHWAR_DB_HOST / PORT / NAME | 127.0.0.1 / 14333 / uniquely owned AlMahwarApiIT_ catalog |
| ALMAHWAR_API_DB_HOST / PORT / NAME | 127.0.0.1 / 14333 / uniquely owned AlMahwarApiSessionIT_ catalog |
| Both DB USER / PASSWORD variables | Generated temporary SQL login and private random password, runtime only |
| Both DB TRUST_SERVER_CERTIFICATE variables | true for the existing self-signed development SQL connection only; SQL encryption remains enabled |
| ALMAHWAR_API_DB_INITIALIZE | true only for the newly provisioned, owned empty disposable API database |
| ALMAHWAR_API_JWT_SECRET | Independently generated base64 32-byte random signing key; runtime environment only |
| almahwar.api.jwt.access-token-ttl | Released property overridden to 20s in the disposable instance to exercise expiry; bundled 15m default and released 60s clock skew unchanged |

The API process never received the SQL administrator password. Existing container administration credentials were obtained privately for narrowly scoped host-side login provisioning/read-only audits and cleanup. Application credentials, SQL passwords, JWT signing secrets and raw access/refresh tokens were not printed, written to evidence, placed in source/defines/APK assets, or passed in command-line arguments. Bundled credential/JWT defaults remain empty.

## 4. Database isolation and protected business data

Before fixture creation, real `AlMahwarDB` schema **1.10.0** was identified through read-only queries. Unique fixture names followed the existing released test conventions. Collision checks established that each named catalog was absent before creation. Cleanup only targeted catalogs/logins created and owned by the coordinator; no unknown database was reset or deleted.

The generated `phase8_` SQL login had the fixture creation permission and access to its owned databases. **Before any fixture provisioning**, `HAS_DBACCESS('AlMahwarDB')` was zero and a real connection attempt was rejected. API connection settings explicitly targeted only the disposable business/security catalogs.

Business fixtures used existing `TemporaryDatabase` support and the released `database/01_create_database.sql`: **schema 1.10.0**. The owned empty API catalog used existing `TemporaryApiDatabase` support and the released explicit initializer: **schema 1.0.0**. No schema source or migration was changed. Permanent `AlMahwarApiDB` was absent before testing and independently confirmed absent after cleanup; it was never provisioned.

Fixture users had generated private identities/passwords for ADMIN, ACCOUNTANT, CASHIER, STOREKEEPER and a restricted CASHIER. Other seeded users were disabled only inside the fixture. Synthetic business data comprised one product (quantity 12.000, unit cost 2.125, sale price 4.750), one customer and one supplier; the released cash-customer seed remained. No sale, invoice, payment or quotation transaction was fabricated or performed from Flutter.

For each completed scope, all real business tables retained their pre/post row counts and `CHECKSUM_AGG(BINARY_CHECKSUM(*))` fingerprints. These are consistency checks, not cryptographic proofs of every row. Isolation, denied test-login access and the absence of real-database mutation statements provide the primary protection. Fixture business-table fingerprints also remained unchanged after UI reads, excluding Users/Audit_Log where genuine authentication activity is expected.

## 5. Android network/build boundary

No trusted local HTTPS endpoint/certificate was available. The request's permitted isolated debug HTTP path was used:

```text
flutter build apk --debug --no-pub
  --dart-define=API_BASE_URL=http://10.0.2.2:49399
  --dart-define=ALLOW_INSECURE_HTTP=true
```

Only the nonsecret endpoint and explicit debug opt-in were passed. `10.0.2.2` is the emulator host alias accepted by the existing private-host validator; the server listened on host loopback. No TLS certificate-validation bypass was added. Development SQL certificate trust is separate from Android HTTPS validation.

The **fresh build succeeded, exit 0**, and the real APK installed/launched on the owned Pixel 7 emulator. APK SHA-256: **`20E4E44FB46988AA3E709818769E0608B5A89C8852E720288635343F07967930`**. A genuine emulator-to-host readiness GET returned **200**, `{"status":"UP"}`, with `Cache-Control: no-store`.

The packaged APK is debuggable with debug cleartext enabled. The accepted debug-only `tools:replace` correction was reused unchanged. Main explicitly retains `usesCleartextTraffic=false` and `allowBackup=false`; profile inherits the production restriction, and release/HTTPS validation remains unchanged. No new manifest correction or production security weakening was needed.

Credential entry used a temporary, debug-only native instrumentation helper targeting the actual Flutter activity. It obtained one-use credentials through a private loopback/owned ADB reverse bridge and injected UI keystrokes in memory. Flutter itself sent the real login/me/refresh requests; the helper did not replace authentication, transport or storage with mocks. UI evidence omitted input fields and scrubbed private identities/tokens. No credential screenshot or clipboard workflow was used. The helper was uninstalled, its bridge removed and the ephemeral emulator stopped afterward.

## 6. Genuine live API results

The accepted server started, connected to the two disposable catalogs and passed readiness. Host-side genuine login/me checks returned the correct identities and permissions for all four roles. Session listing worked; logout returned 204 and the old access credential subsequently received 401. Protected unauthenticated Dashboard returned 401. All exercised successful/forbidden business responses had `Cache-Control: no-store`.

The table is **fresh host HTTP evidence**, distinct from native UI observations below. All paths are under `/api/v1`; me is `/auth/me`.

| GET | ADMIN (62 permissions) | ACCOUNTANT (33) | CASHIER (19) | STOREKEEPER (13) |
| --- | --- | --- | --- | --- |
| /auth/me | 200 | 200 | 200 | 200 |
| /manager/dashboard | 200 | 200 | 200 | 200 |
| /products | 200 | 200 | 200 | 200 |
| /manager/customers | 200 | 200 | 200 | 403 |
| /manager/suppliers | 200 | 200 | 403 | 200 |
| /manager/inventory/summary | 200 | 403 | 403 | 200 |
| /manager/invoices | 200 | 200 | 200 | 403 |
| /manager/quotations | 200 | 200 | 200 | 403 |
| /manager/daily-summary | 200 | 200 | 200 | 200 |
| /manager/audit | 200 | 403 | 403 | 403 |
| /manager/admin/users | 200 | 403 | 403 | 403 |

No backend regression suite was substituted for a live API process. No permanent API database or real business transaction was used.

## 7. Native protected screens and actual data

The Arabic login screen opened and real ADMIN authentication reached the authorized Dashboard. `/me` established server permissions through the unchanged Flutter authentication flow. All four representative roles subsequently authenticated on the same real debug APK.

| ADMIN destination | Actual native observation |
| --- | --- |
| Dashboard | Server business date/period, real zero sales/profit/expenses and permitted inventory data; refresh completed |
| Products/inventory | Actual fixture product, quantity 12.000, sale price 4.750 and authorized cost 2.125 |
| Sales | Real zero-activity summary and empty top-products state |
| Invoices | Real GET-backed empty list with server-backed filter/search controls |
| Customers | Synthetic customer plus released cash customer; authorized balance data |
| Suppliers | Synthetic supplier; authorized balance data |
| Quotations | Real empty quotation list |
| Reports | Authorized report destinations and actual Daily Summary; current inventory cost 25.500, one active product and real zero activity |
| Audit | Genuine fixture authentication events; no raw tokens/passwords in the inspected labels |
| User administration | Actual temporary users displayed; no administration write was submitted |
| Profile/devices | Own account, real current/revoked sessions and current-device indicator |

The initial full ADMIN drawer traversal was observed during this task. A later verification-helper invocation overwrote its first durable screen file; the retained `native-screens-ADMIN.json` contains only the later Sales/Reports subset. Separate Dashboard, invoice, Daily Summary and session evidence remain. The table records direct observations; it does not imply a retained screenshot/complete trace for every screen. The helper was then changed to append subsequent safe evidence; production code was unchanged.

ACCOUNTANT's native drawer excluded Audit/User Administration. CASHIER additionally excluded Suppliers; its product cost, Dashboard/sales profit and customer balances were absent from the inspected native UI/semantics. A redacted balance was not shown as zero. STOREKEEPER excluded Sales/Customers/Quotations/Audit/User Administration; its supplier balances were absent, and the report hub contained only permitted daily/inventory destinations.

ACCOUNTANT and STOREKEEPER legitimately have product-cost permission in released Shared Core. Showing their authorized product cost/current inventory value is not a redaction failure. Fine-grained server permission checks remain authoritative rather than role-name-only Flutter gates. Representative native labels and host JSON redaction were checked; exhaustive native TalkBack/physical-device coverage is not claimed.

## 8. Native authentication lifecycle case ledger

PASS means the stated native behavior was actually exercised in this disposable environment. NOT VERIFIED is not a production pass or a diagnosed application defect.

| Case | Status | Evidence/limit |
| --- | --- | --- |
| Genuine login and me | PASS | ADMIN, ACCOUNTANT, CASHIER, STOREKEEPER; actual Flutter requests and server session/permission state |
| Restricted authentication | PASS, limited | Native restricted CASHIER sessions were created with restricted=true, no refresh token and no persisted refresh key |
| Complete must-change-password screen flow | NOT VERIFIED | Private UI automation did not complete the restricted form/callback; no completed native screen flow certification |
| Genuine native password change and new-password login | NOT VERIFIED | Server state did not establish a successful change; no production change/workaround was made |
| Access expiry and refresh | PASS | Test-only 20s TTL plus unchanged 60s skew; same native ADMIN session rotated during real protected requests after expiry |
| Contended singleflight refresh | NOT VERIFIED natively | Inherited concurrent-refresh tests pass; natural native rotations were observed, but no controlled simultaneous-401 native stress proof |
| Own session listing | PASS | Native Profile rendered real current/historical sessions and current-device label |
| Logout | PASS | Native confirmation for ACCOUNTANT/CASHIER/STOREKEEPER returned to login; server revocation and refresh-key deletion checked in representative probes |
| Logout-all | PASS, current-session scope | CASHIER Profile action and explicit confirmation returned to login; current native session revoked and refresh key absent. Multiple simultaneously active devices were not certified |
| Revocation from another session | PASS | A separate genuine ADMIN HTTP session called the released session DELETE; one native session revoked |
| Rejected revoked access / protected cleanup | PASS | Host old-access me rejected with 401; native resume returned to login, protected state disappeared and persisted refresh key was removed |
| Background/resume | PASS, exercised interval | HOME/resume retained the same app PID and authenticated state; subsequent revocation was detected safely |
| Termination/restart | PASS | Ordinary force-stop and normal launcher restart restored the authenticated ADMIN Dashboard without re-entering credentials |
| Secure refresh restoration | PASS | Actual Android plugin persistence and server rotation restored the same session across process termination |
| Secure replacement/rotation | PASS | Old refresh rows consumed, new rows created and ciphertext fingerprint changed |
| Secure token removal | PASS | Refresh key absent after representative native logout/logout-all/revocation probes |
| Absolute refresh-session expiry | NOT VERIFIED | No waiting for full session lifetime or direct database expiry manipulation |

Temporary restricted sessions from unfinished form attempts remained only in the owned disposable API database and were removed with final catalog cleanup. They did not gain normal refresh-token persistence or access to real databases.

## 9. Native secure-storage evidence

The unchanged `SecureTokenVault` uses the existing `flutter_secure_storage` 11.2.0 plugin with Android Keystore-backed cipher implementation and an API-origin-scoped `manager.refresh.v1` key. Access tokens remain controller memory state; vault operations serialize rotation/deletion.

The actual debug application's private preferences were inspected only inside a host verifier's memory using debug `run-as`. Raw preferences were never output or saved. Evidence stores byte counts, ciphertext SHA-256 and boolean key/token checks only:

- After ADMIN login/normal restore: refresh key present; two refresh records, one consumed; no raw refresh/access-token pattern in persisted preferences.
- After natural access expiry/route requests: three refresh records, two consumed, same session creation timestamp, changed encrypted-persistence fingerprint.
- After ordinary force-stop/restart: same original native session, six refresh records/five consumed; authenticated Dashboard restored. The count includes intervening natural rotations, not a claim of six rotations caused by one restart.
- After external revocation and representative logout/logout-all: refresh key absent; raw refresh/access-token patterns absent.

This establishes exercised native debug write/restore/rotate/remove behavior. It does not certify hardware-backed key protection, lock-screen/biometric changes, key invalidation, storage failure, reinstall, backup/restore or a signed release/physical-device lifecycle.

## 10. Security observations and source integrity

Production scans found no direct SQL references, raw JWT/refresh/generated-credential patterns, TLS bypass, `LogInterceptor`, `print` or `debugPrint` in Flutter `lib`. Sanitized API output and the inspected recent Android logs had no raw token/generated-password pattern. No app fatal match was found in the inspected log windows; emulator UWB faults were observed. These are scoped scans, not claims about uninspected historical buffers.

The live app used business GETs only. Genuine authentication/session POSTs and an authorized own-session DELETE were exercised. No POS/business/quotation mutation, permanent user deletion or administration create/edit/disable/enable/reset was executed from Flutter. No direct Flutter-to-SQL connection exists. Host-side fixture provisioning/read-only auditing remained separate from the native application.

Financial redaction was checked against actual native semantics for representative roles and backend responses. Logout/revocation removed protected UI. All inherited session/permission, uncertain-write, no-double-submit, exact-decimal, stale-response and pagination tests passed. Explicit live permission reassignment and ambiguous native administrative write retries were not exercised; source/auth/dependency/contract integrity was preserved rather than claiming new native coverage for those cases.

## 11. Fresh Flutter regression versus historical evidence

Freshly executed from `mobile/manager_app`:

| Command | Result |
| --- | --- |
| dart format --output=none --set-exit-if-changed lib test | 98 files, 0 changed, exit 0 |
| flutter analyze --no-pub | No issues found, exit 0 |
| flutter test --no-pub | **699 passed, 0 failures, 0 skips**, exit 0 |

Accounting: **699 inherited + 0 new = 699 total**. JSON reporting includes 21 hidden loader events in addition to the 699 actual visible tests; the terminal successful event was checked. No test was changed or weakened. Source/test hashes stayed unchanged after execution, so successful regression was not unnecessarily repeated.

Phase 7's **558 responsive cases / 69 Arabic-font checks**, accepted API **637 tests**, and Desktop **373 discovered /168 executed /205 skipped** remain **historical baselines**. They were not independently rerun in this Part A. Native Android integration above was freshly executed and is distinct from those suites.

## 12. Guaranteed cleanup and independent final audit

The coordinator owned unique fixtures/login/process handles and used `finally` cleanup on success, failed harness attempts and safety-lease exit. Temporary verification tooling was iterated without changing production implementation. Earlier scopes were fully cleaned before new unique fixtures were created.

A host clock discontinuity was observed between the earlier native run and its continuation. The finite safety lease closed that owned API/fixture scope; its saved results confirm successful cleanup. A final isolated scope was created to complete STOREKEEPER and logout-all checks. No clock modification was performed by this task, and no cause of the discontinuity is inferred.

Final actions stopped the owned API, dropped only its owned disposable API/business databases, removed its generated SQL login, removed the task's ADB reverse bridge, uninstalled the temporary instrumentation helper, stopped the app and shut down only owned emulator-5558. Ordinary host/native sessions were explicitly logged out or revoked where exercised; remaining fixture session records ceased to exist when the owned API database was dropped.

Both the coordinator's final checks and a separate fresh read-only SQL audit established:

```text
BusinessSchema=1.10.0
DisposableApiSchemaDuringRun=1.0.0
PermanentApiDatabase=0
DisposableBusinessDatabases=0
DisposableApiDatabases=0
TemporarySqlLogins=0
RealBusinessUnchanged=true
FixtureBusinessReadOnly=true
CleanupErrors=0
```

Owned API PID 37164 is absent; owned emulator PID 32232/serial emulator-5558 are absent; final ADB device inventory is empty. Existing `almahwar-sql`, Hadoop and multica containers remained running. No existing AVD, container, backup or unknown resource was deleted. There is no remaining cleanup blocker.

## 13. Protected refs, Desktop stash and backups

All pre-existing local/origin branch refs, tag objects, actual remote heads and actual remote tag/peeled refs matched the entry snapshot. The only additional ref is this local Phase 8 branch at the unchanged accepted SHA.

| Protected branch | Unchanged SHA |
| --- | --- |
| main | c0234e49e2bf00e094f2bf5de68bebd7fe5f963b |
| api-development | 5cd2819eb2495123c1a41b5db324c267f389d5ee |
| api-core-adoption | 0aa9f7b9239f6c9a060ba00c8f0309993a9deb99 |
| api-phase2-auth | 32c673406025c6c78f9adae2c61e604c954c1604 |
| api-phase3-manager-read | 75bd8d208699261418e97bbf166547820d0742bf |
| api-phase4-manager-admin | 1b9a46f260d513bfbf3c6391d98a890a3de00878 |
| api-phase5-manager-final | b86e66eb2fe24f672a73324518813909feca7c62 |
| flutter-manager-development | 2592d031550ebd33206c41f855e48f2d2c362f27 |
| flutter-manager-phase2-dashboard | b17b6df4fd5196af8ee156356f749063b4e9b684 |
| flutter-manager-phase3-inventory | ae68338e768a33c48f72b0d65caa33c361600d3a |
| flutter-manager-phase4-sales | 464aa6c0da386f710e4db888fe22c746cf026597 |
| flutter-manager-phase5-parties | c7510ee3440471681a93b4b882a8861ad19cec8c |
| flutter-manager-phase6-final-features | 28883bc36d13a9becfe892dbc3f30e9d04bd1704 |
| flutter-manager-phase7-release-readiness | 911464e19c01c43555d115eef947f155d4bd7dc0 |
| codex/desktop-login-redesign | 2592d031550ebd33206c41f855e48f2d2c362f27 |
| desktop-1.0.1-core | 5f9e9e0a740279496e3603caf556fbb31f8a5fe3 |

Origin counterparts retain these SHAs wherever present in the baseline. `api-development` and the Desktop-only branches had no origin counterpart. Release tag object SHAs remain v1.0.0 `7202640ad2f44ef879920cff2d2b08ab13fb6446` and v1.0.1 `8b97ebb60e333522f784e63935cb2ad2b088c627`.

Desktop redesign stash **`df78653663bcbfa5c331a83f2a79bc39d4076f52`** is unchanged, not restored, altered, dropped or committed. Independent backups in `20261008-3d7c4ed8` retain original login.fxml/styles.css hashes. `docker` retains its original empty-file SHA-256, remains untracked and was not modified or staged.

## 14. Remaining Part B/release limitations

1. Complete the native restricted password-change/new-password flow with reliable private input verification; the current attempt is NOT VERIFIED, not a confirmed production defect.
2. Native simultaneous-refresh stress, multi-active-device logout-all, absolute session expiry and explicit live permission-removal cases remain unverified.
3. Physical-device/TalkBack and full native protected-screen accessibility matrix, hardware Back behavior and keystore failure/lock/reinstall/backup cases remain open. Automation Back occasionally returned to the launcher; the responsible boundary was not established and no production behavior was changed.
4. Resolve emulator UWB/System UI stability and unaccepted licenses/toolchain warnings for reproducible release testing. No new System UI certification is claimed from this run.
5. Proper production HTTPS deployment and separately authorized permanent security-database provisioning remain open; the approved debug loopback HTTP run does not certify production transport.
6. Release signing, branding/metadata review, packaging and distribution remain separately authorized work. No signed production APK/AAB was created.

Actual Android login and the exercised native secure-storage lifecycle are now established for this disposable debug environment; broader Phase 7 release blockers remain limited by the scope stated above. No unresolved critical isolation, cleanup, protected-source or required actual ADMIN-login failure remains.

## 15. Evidence and final Git audit

Ignored `target/flutter-phase8/` contains baseline/source/ref snapshots, doctor/devices/build logs, format/analyze/JSON test traces, sanitized API output, native role/drawer/screen captures, session/persistence fingerprints, revocation/background/restart/logout evidence and cleanup audits. `sixth-run-live-results.json` retains the four-role native login/revocation scope; `seventh-run-live-results.json` retains the final isolated scope. `independent-cleanup-audit.json`, `final-resources.json`, `final-security-scan.json` and `boundary-audit.json` contain the final assertions. No raw secret/credential screenshot is an evidence artifact.

The requested branch, HEAD, status, whitespace and staged-path commands were checked. The index is empty; `git diff --check` succeeds; no tracked file differs. Individual untracked inventory contains only the unrelated `docker` and this report.

Exact final `git status --short`:

```text
?? docker
?? docs/FLUTTER_MANAGER_PHASE8_PART_A_ANDROID_INTEGRATION_REPORT.md
```

**Stop for human review and Part B.** No commit, push, merge, tag, PR or deployment is performed.

FLUTTER MANAGER PHASE 8 PART A READY FOR REVIEW


## End preserved historical Part A checkpoint

---

## Later completion reconciliation — recovered on 2026-10-10

This appendix updates evidence status without changing the preserved historical checkpoint above. No native test was repeated during this recovery. The current branch is flutter-manager-phase8-android-integration and HEAD remains 911464e19c01c43555d115eef947f155d4bd7dc0.

### Provenance and limits

The later records are under target/flutter-phase8/completion/. They were inspected together with target/flutter-phase8/native-helper/EntryInstrumentation.java, Phase8Live.java and completion/Phase8State.java, rather than accepting a prior summary as proof.

The temporary instrumentation driver launched the actual Flutter Activity, entered generated credentials into native Flutter fields, clicked login, verified the three-field mandatory-password screen and its Arabic warning, entered current/new/confirmation passwords, clicked save, waited for the two-field login screen, entered the new password and waited for Dashboard. Only then did it emit AUTHENTICATED and the completion callback. It did not implement or mock the Flutter login/change-password/refresh HTTP operations.

The host callback separately checked the fixture user's must_change_password flag, attempted old-password login and old restricted access /auth/me, and performed a separate new-password login/me followed by logout. These are HOST HTTP and SERVER STATE assertions, not substitutes for native interaction.

completion/native-restricted-result.json contains a subsequent UI-capture error, "native semantics unavailable". That file is not a successful screenshot or a complete native trace. The successful earlier callback and SQL state, followed by completion/native-after-password-restart.json showing the actual Dashboard after ordinary restart, provide the retained supporting evidence. No complete intermediate screenshot archive or independently signed trace is claimed.

### Updated case ledger

| Case | Updated verified scope | Retained evidence |
| --- | --- | --- |
| Mandatory password restriction | NATIVE UI PASS: three required fields and mandatory warning observed; HOST HTTP PASS: restricted Dashboard 403 | completion/live-final.json mandatoryNativePasswordScreen=true / restrictedDashboardStatus=403; actual driver stage checks |
| Password change | NATIVE UI PASS: submitted through Flutter form; SERVER STATE PASS: flag cleared and old restricted sessions revoked | actual driver; live-final.json nativeRestrictedPasswordChange; state-password-restored.json |
| New-password login | NATIVE UI PASS: actual new-password form login reached Dashboard; HOST HTTP PASS: separate new-credential me 200 and unrestricted refresh issuance | nativeEntryStage=AUTHENTICATED; native-after-password-restart.json; live-final.json |
| Old password rejection | HOST HTTP PASS: old-password POST /auth/login 401 | live-final.json oldPasswordStatus=401 |
| Old access rejection | HOST HTTP PASS: previously held restricted peer access GET /auth/me 401 | live-final.json oldRestrictedAccessStatus=401; not a separate replay of every native credential |
| Refresh restoration and rotation | NATIVE UI PASS + SERVER STATE PASS: authenticated Dashboard after ordinary restart; same new native session has two refresh rows, one consumed | native-after-password-restart.json; state-password-restored.json |
| Multi-session logout-all | NATIVE UI PASS: Profile confirmation and transition to login; SERVER STATE PASS: two active sessions before, zero after, all corresponding rows revoked | logout-all-confirmation.json; logout-all-login.json; live-final.json; state-logout-all-ended.json |
| Peer credentials after logout-all | HOST HTTP PASS: peer access and peer refresh both 401 | live-final.json peerAccessAfter=401 / peerRefreshAfter=401 |
| Native session after logout-all | SERVER STATE PASS: native row revoked; NATIVE UI PASS: normal force-stop/restart remains login without reviving sessions | logout-all-restart.json; state-logout-all-restarted.json |
| Explicit native access/refresh replay after multi-session logout-all | NOT VERIFIED independently | No retained host replay of each native credential; do not inflate the peer 401 assertions to cover both devices separately |
| Direct SecureTokenVault deletion in this later completion | NOT VERIFIED directly | Logged-out UI and server revocation do not prove key deletion; completion/security-scan.json states androidStorageSecretsReadInCompletion=false |
| Earlier representative secure-storage removal | PASS, historical native-debug preference-key observation only | session-evidence-admin-revoked.json and session-evidence-logout-all.json report refreshKeyPresent=false; separate from the later completion and from mocked vault tests |
| Simultaneous native refresh stress / absolute session expiry | NOT VERIFIED natively | Existing Flutter regression covers concurrent refresh; no new native stress or full-lifetime expiry exercise |

The later logout-all case used one real Android app session and one independently authenticated host session. It certifies two concurrently active server sessions, not two physical Android devices.

### Isolation and cleanup for the later completion

completion/live-final.json records the fixture login denied access to real AlMahwarDB before provisioning, fixture business schema 1.10.0, disposable API schema 1.0.0, fixtureBusinessReadOnly=true, realMetadataUnchanged=true and realBusinessRowsRead=false. Authentication changed only fixture identities/session state. Completion cleanup records zero disposable business databases, zero disposable API databases, zero phase8_ SQL logins and no cleanup errors. completion/independent-cleanup-audit.json separately corroborates zero counts, business schema 1.10.0 and absence of permanent AlMahwarApiDB. completion/final-resources.json records the owned API/emulator stopped. These completion observations are historical.

The initial checkpoint used the stated read-only real-business fingerprint queries; the later completion protection used schema/catalog metadata instead. Its realMetadataUnchanged flag is not a cryptographic all-row business-data proof.

### Recovery and current verification boundary

The original report at docs/FLUTTER_MANAGER_PHASE8_PART_A_ANDROID_INTEGRATION_REPORT.md was 0 bytes at recovery entry. The preserved checkpoint was recovered from completion/part-a-report-before-completion.md, 27,800 bytes, SHA-256 8833D29FC74E607A6626E29D7BA1ED7C6D53A55B206ED7E4DFCC7504DD20B6CE. That archive remains unchanged. Its entire original byte sequence is retained in this report between the checkpoint markers. No unavailable, later full report text was fabricated.

The new Part B report documents fresh format/analyze/699-test regression, read-only SQL cleanup confirmation, source/APK scans, protected assets and exact final Git status. Earlier uses of "fresh" inside the preserved checkpoint refer to its original execution, not this recovery.

Hardware-backed keystore guarantees, key invalidation, reinstall/backup restore, physical-device/TalkBack certification, simultaneous native refresh stress, absolute session expiry, live permission reassignment, hardware Back and exhaustive native network/accessibility coverage remain NOT VERIFIED. No Android keystore secret or private preference was read during recovery. Production HTTPS deployment, permanent API security-database provisioning, licenses, signing, backup/restore operations and monitoring remain separate release work.

No production source/test, dependency, schema, backend, contract or auth protocol change; no staging, commit, push, merge, tag, PR or deployment.

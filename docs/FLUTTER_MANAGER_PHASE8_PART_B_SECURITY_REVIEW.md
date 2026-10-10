# Flutter Manager Phase 8 — Part B security and integration review

Date: 2026-10-10. **READY FOR HUMAN REVIEW**, with the native and production limitations explicitly open below. This is documentation recovery and verification, not production certification.

Branch: **flutter-manager-phase8-android-integration**. Accepted Phase 7 base and unchanged current HEAD: **911464e19c01c43555d115eef947f155d4bd7dc0**. No production implementation was restarted, changed or refactored. No test was added or weakened. No staging, commit, push, merge, rebase, tag, PR, Phase 9, deployment, signed production APK/AAB or permanent API database provisioning was performed.

## 1. Part A recovery and evidence preservation

At entry, docs/FLUTTER_MANAGER_PHASE8_PART_A_ANDROID_INTEGRATION_REPORT.md existed at **0 bytes**, SHA-256 E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855. Its cause of truncation is not established here.

The trustworthy saved checkpoint was target/flutter-phase8/completion/part-a-report-before-completion.md: **27,800 bytes**, SHA-256 **8833D29FC74E607A6626E29D7BA1ED7C6D53A55B206ED7E4DFCC7504DD20B6CE**. It contains the original report, including the incomplete initial must-change-password attempt and current-session-only logout-all result.

Recovery retains the entire original byte sequence verbatim between explicit checkpoint markers. A recovery notice and a separate later-completion reconciliation were added. The archived copy was not edited. The restored report is **36,216 bytes**, SHA-256 **E0067C31DF5570209A0E03F92BA9194D0FB40C65B263BA88E006562EDAC9FBEF**. Its previous checkpoint statements are preserved as history, not silently rewritten. Unavailable later full-report text was not invented.

Existing direct-file evidence under target/flutter-phase8/ and completion/, plus the native helper source, was hashed before this recovery. Final comparison verifies it remains unchanged. New helpers, logs and audit results are isolated under ignored target/flutter-phase8/part-b-recovery/. The original zero-byte state, source archive hash and byte-preservation check are recorded there in recovery-baseline.json and recovery-result.json.

## 2. Exact reviewable manifest

Relative to HEAD: **0 modified tracked files + 2 new documentation files**.

| Git change | Path | This continuation |
| --- | --- | --- |
| New | docs/FLUTTER_MANAGER_PHASE8_PART_A_ANDROID_INTEGRATION_REPORT.md | Recover existing empty untracked report; preserve historical checkpoint and append evidence reconciliation |
| New | docs/FLUTTER_MANAGER_PHASE8_PART_B_SECURITY_REVIEW.md | This requested review |

No Flutter production or test file was changed. The unrelated untracked docker is unchanged and excluded from the reviewable manifest. Temporary helpers, APKs, screenshots, logs, runtime extracts and build outputs remain ignored and outside the intended commit manifest. The index remains empty.

## 3. Evidence inspected and execution accounting

The preserved Part A checkpoint, accepted Phase 7 readiness report, frozen API_MANAGER_CONTRACT_V1.md, current Flutter authentication/storage/transport/controllers/access gates and inherited tests were inspected.

Later completion evidence was read directly, rather than treating a Codex summary as proof:

- completion/live-final.json and live-before-stop.json.
- completion/state-password-restored.json, state-logout-all-ended.json and state-logout-all-restarted.json.
- completion/native-after-password-restart.json, logout-all-confirmation.json, logout-all-login.json and logout-all-restart.json.
- completion/native-restricted-result.json, which records a subsequent UI-capture error and is not a passing screenshot.
- completion/Phase8State.java; target/flutter-phase8/Phase8Live.java; native-helper/EntryInstrumentation.java.
- completion/independent-cleanup-audit.json, final-resources.json, security-scan.json and historical regression traces.
- Earlier native role/drawer/session/revocation/background/restart evidence, including session-evidence-admin-start.json, admin-navigation.json, admin-restart.json, admin-revoked.json and logout-all.json.

**Fresh in this recovery/Part B:** format, analyze, full 699-test Flutter regression, source security scan, scan of the accepted existing debug APK, existing merged-manifest inspection, read-only SQL catalog/schema cleanup query, ADB/process/container inventory and Git/source/ref/stash/backup audits.

**Reused historical evidence:** all native UI interactions, actual API login/password/refresh/revocation/logout-all runs, role HTTP matrix, Android build/emulator startup, 558 responsive checks, 69 Arabic-font checks, accepted API 637 and Desktop 373 discovered /168 executed /205 skipped. None of those native/live/backend/visual suites was rerun here. No new API process, emulator, disposable database or test SQL login was created.

## 4. Native versus host HTTP and server-state reconciliation

PASS below means the stated scope has retained supporting evidence. A historical native pass is not a newly executed native test or production certification.

The disposable instrumentation driver launched the actual Flutter Activity, used UI keystrokes and clicks, verified the mandatory three-field password form and Arabic warning, submitted current/new/confirmation passwords, waited for login, entered the new password and waited for Dashboard. Its AUTHENTICATED callback occurs only after that Dashboard assertion. Flutter performed the authentication/change-password network operations; the helper did not replace transport, authentication or SecureTokenVault with mocks.

The host callback separately checked fixture SQL state and old/new credentials. Phase8State selects session flags/counts/timestamps and identifies native rows by the actual Manager app device label. It does not read tokens, hashes, Android preferences or keystore keys.

| Case | Verified status and scope | Concrete supporting record |
| --- | --- | --- |
| Genuine four-role Android login and /me permissions | NATIVE UI PASS, historical; HOST HTTP PASS, historical, independently recorded role responses | sixth-run-live-results.json; retained role/drawer captures; original checkpoint matrix |
| Must-change-password UI | NATIVE UI PASS, later completion: required three fields and mandatory Arabic warning | Driver checks plus live-final.json mandatoryNativePasswordScreen=true |
| Restricted Manager access | HOST HTTP PASS: restricted peer Dashboard 403; SERVER STATE PASS: native restricted session had no refresh rows | live-final.json restrictedDashboardStatus=403; state-password-restored.json |
| Password change | NATIVE UI PASS: actual Flutter save action; SERVER STATE PASS: must_change_password false and old restricted native/host sessions revoked | Driver callback; nativeRestrictedPasswordChange; state-password-restored.json |
| New-password login | NATIVE UI PASS: actual new-password form login reached Dashboard; HOST HTTP PASS: separate fresh login issued normal refresh and /me 200 | nativeEntryStage=AUTHENTICATED; native-after-password-restart.json; newCredentialsMeStatus=200 |
| Old password rejection | HOST HTTP PASS: old-password POST /auth/login 401 | oldPasswordStatus=401 |
| Old access rejection | HOST HTTP PASS: old restricted peer GET /auth/me 401 | oldRestrictedAccessStatus=401; not replay of every native credential |
| Restoration and rotation after ordinary restart | NATIVE UI PASS + SERVER STATE PASS: Dashboard restored without re-entry; same new native session has 2 refresh rows, 1 consumed | native-after-password-restart.json; state-password-restored.json |
| Multi-session logout-all | NATIVE UI PASS: Profile confirmation then login; SERVER STATE PASS: 2 active sessions before, 0 after, native and peer rows revoked | logout-all-confirmation.json; logout-all-login.json; live-final.json; state-logout-all-ended.json |
| Peer access and refresh after logout-all | HOST HTTP PASS: each 401 | peerAccessAfter=401; peerRefreshAfter=401 |
| Android remains logged out after restart | NATIVE UI PASS + SERVER STATE PASS: login remains; revoked state and refresh counts unchanged | logout-all-restart.json; state-logout-all-restarted.json |
| Each old native credential independently replayed after multi-session logout-all | NOT VERIFIED independently | Only peer credentials have retained direct host 401 replay assertions |
| Cross-session revocation and terminal signout | NATIVE UI PASS + HOST HTTP PASS + SERVER STATE PASS, earlier scope: genuine separate ADMIN session revoked native session; native resume returned to login | sixth-run-live-results.json; native-after-revoke.json; session-evidence-admin-revoked.json |
| Representative ordinary logout | NATIVE UI PASS + SERVER STATE PASS, earlier role scopes | native-accountant-logout.json, native-cashier-logout.json, session-evidence-accountant-logout.json |
| Background/resume | NATIVE UI PASS for exercised earlier interval, plus fresh widget PASS | background-evidence.json / resume-evidence.json; inherited lifecycle tests |
| Simultaneous native 401 stress / absolute session expiry | NOT VERIFIED natively | Natural expiry/rotation and mocked contention tests do not establish native stress or full-lifetime expiry |

The later logout-all case is one Android app session plus one independently authenticated host session. It establishes two active server sessions, not two physical Android devices.

completion/native-restricted-result.json reports "native semantics unavailable" after the successful callback. It is excluded as passing UI evidence. The retained callback checks, server state and subsequent ordinary-restart Dashboard capture corroborate the completed flow. There is no retained complete intermediate screenshot archive or independently signed trace.

The original checkpoint's incomplete password-flow and current-session-only logout-all statements remain valid descriptions of the first attempts. The later completion provides the additional scoped passes above.

## 5. Secure-storage lifecycle and honest limits

Current source uses SecureTokenVault with the existing flutter_secure_storage plugin. Refresh storage is API-origin scoped; write/delete operations are serialized. Access tokens remain controller memory state. No insecure fallback path was found. The fresh mocked test "secure vault isolates API origins and serializes deletion after rotation" passes; it uses FlutterSecureStorage.setMockInitialValues and is explicitly not a native keystore test.

Earlier historical native-debug probes read private preferences only inside a verifier's memory and retained boolean key checks, byte counts and ciphertext fingerprints. Re-read records show refreshKeyPresent=true during the original ADMIN session, changed fingerprints across rotation, and refreshKeyPresent=false in representative original logout-all/revocation probes. Their verifier source establishes that these are native debug-preference observations, not MemoryVault results. No raw preference or token value was displayed or extracted during this review; the probe was not executed again.

Later completion did not inspect Android private storage. It records androidStorageSecretsReadInCompletion=false. Its restored Dashboard/server rotation supports functional persistence/restoration. **Direct SecureTokenVault key deletion after that later password change or two-session logout-all is NOT VERIFIED.** Logged-out UI plus revoked server sessions does not prove deletion. Earlier representative deletion evidence must not be attributed to the later case.

NOT VERIFIED: hardware-backed guarantees, keystore key invalidation, lock-screen/biometric changes, storage failure on a real device, reinstall/backup restoration, physical-device or signed-release lifecycle. No Android keystore key or actual secret was accessed.

## 6. Permissions, financial privacy and business boundaries

Released server permissions remain authoritative. CurrentUser.allows checks actual /me codes. Inventory/Sales/Party access classes independently gate identity, inventory, cost, profit and balances. Audit/users additionally require ADMIN role plus AUDIT_LOG/USERS_VIEW; each write requires its specific released permission. A role name alone does not grant those routes.

Historical host matrix and representative native labels remain:

| Role | Customers | Suppliers | Inventory summary | Invoices / quotations | Audit / user admin | Financial observations |
| --- | --- | --- | --- | --- | --- | --- |
| ADMIN | 200 | 200 | 200 | 200 | 200 | Authorized cost/profit/balances |
| ACCOUNTANT | 200 | 200 | 403 | 200 | 403 | Authorized product cost and financial views; inventory summary has independent gates |
| CASHIER | 200 | 403 | 403 | 200 | 403 | Product cost, profit and customer balance absent; no fake redacted zero |
| STOREKEEPER | 403 | 200 | 200 | 403 | 403 | Authorized product cost; supplier balance absent; permitted daily/inventory reports only |

All four /auth/me and Dashboard requests were 200 in the historical matrix. These are host responses; retained native drawer/semantics evidence separately corroborates representative availability/redaction. The initial complete ADMIN screen trace was overwritten by a later helper invocation as disclosed in the preserved report; a retained full trace for every ADMIN destination is not claimed.

Fresh repository/state/widget tests pass for independent cost/profit/balance redaction, no financial leaks through text/semantics, permission loss clearing open protected views before slow /me, stale privileged-response rejection, Audit/admin restrictions and logout removal of protected routes. Exact monetary/quantity strings retain three decimal places; formatting uses strings/BigInt rather than monetary binary floating-point conversion.

All business repositories issue GETs. Current Manager mutations remain exactly the released create, edit, disable, enable and reset-password user operations. Auth/session/password operations are separate. There is no permanent user deletion or Flutter sales/payment/inventory/quotation mutation route. The word DELETE in audit_repository.dart is an allowed audit event filter, not an HTTP write.

Explicit confirmations, one pending administration write, permission recheck, password-field clearing/obscuring, no result persistence and uncertain-write lock until reread plus explicit acknowledgement remain intact. Last-active-admin enforcement stays server-authoritative; this review did not rerun backend concurrency/last-admin suites or perform native administration writes.

## 7. Android HTTP/HTTPS, transport and secrecy

Main manifest still sets usesCleartextTraffic=false and allowBackup=false. The accepted Phase 7 tools:replace correction exists only in debug. Profile has no cleartext override. Fresh inspection of existing merged build artifacts shows debug=true, profile=false and release=false; backup=false in all three. These merged artifacts were inspected, not rebuilt during Part B.

AppConfig rejects HTTP by default. HTTP requires debug mode, ALLOW_INSECURE_HTTP=true and a private host. Nondebug/profile/release require HTTPS even if opt-in is supplied. URLs containing credentials, query, fragment or unsupported paths are rejected. Dio uses normal certificate validation, rejects badCertificate safely and disables redirects. No certificate-validation bypass was found.

All three inspected variants request INTERNET and the AndroidX-generated application signature permission com.almahwar.manager_app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No location/camera/contacts/storage permission was found. The latter is not a dangerous user permission.

Fresh source scans found zero matches for direct SQL, logging calls/interceptors, TLS bypass, insecure storage fallback, literal credentials and embedded token/private-key patterns. All 153 nonempty ZIP entries in the accepted debug APK were scanned for JWT/GitHub-token/private-key patterns with zero matches. APK SHA-256 remains **20E4E44FB46988AA3E709818769E0608B5A89C8852E720288635343F07967930**. These are scoped pattern scans, not exhaustive secret certification. No new secret could have been introduced through production changes: all protected source hashes and the accepted APK hash remain unchanged.

Historical sanitized API/native log scans report no generated password/token patterns or app fatal match in the inspected windows. Those logs were not regenerated. Flutter production contains no response logging path; LoginTokens diagnostics redact values. Financial semantic tests pass. UWB emulator faults remain historical platform issues. Real production HTTPS connectivity has not been exercised.

## 8. Performance, network resilience and Android QA

Fresh tests and current source support:

- Twelve simultaneous mocked 401-protected requests share **one** refresh; late old-token 401 reuses the rotation. ApiClient has at most two attempts; second 401 terminates. No 403/404/5xx generic refresh/retry loop.
- Transient network/refresh failure preserves the credential for a controlled later retry; terminal revoked refresh clears memory and vault. Logout clears locally even when the network fails. Network/certificate errors use safe localized messages.
- Scope/generation guards reject account/session/permission/query changes and late responses. 403 clears sensitive cache before a slow permission revalidation. Dispose removes listeners, cancels debounce/monitor timers and rejects late publication; already sent requests are not claimed to be actively cancelled.
- Paged repositories bound page 0..10000 and size 1..100, normally 20. Singleflight, same-page retry, stop-at-end/empty-page guards and overlap deduplication pass. Own-session history remains unpaginated as in the frozen contract; long-term retention/growth requires operations review.
- Ambiguous TIMEOUT/HTTP_503/INVALID_RESPONSE writes lock until state reread and explicit acknowledgement. No automatic ambiguous-write retry or double submission. A rejected 401 can receive the existing one-refresh retry; it is distinct from an uncertain write outcome.
- Profile initial/concurrent refresh shares one read. Paused 61/121-second widget probes make zero periodic /me requests; resume validates once. Repeated drawer navigation keeps one root route and logout removes protected pushed routes.

Representative fresh passing cases: "simultaneous 401 responses share a single refresh rotation", "second unauthorized response ends session without refresh loop", "refresh singleflight and dispose ignores late completion", "403 clears cached data before waiting for slow /me", "pagination singleflight, same-page retry and overlap deduplication", "ambiguous HTTP_503 locks writes until reread and acknowledgement", and "profit cost and balances cannot leak through text or semantics". security-test-index.json maps actual passed IDs/names; overlapping category counts are not additional tests.

These measurements are test request counts/lifecycle probes, not native latency, throughput, memory, frame-rate, battery, load or soak benchmarks.

Android QA reused the actual emulator evidence: Arabic login, four-role protected navigation, keyboard-driven password form, login/logout, normal restart/restore, HOME/resume and revocation/terminal signout. Safe errors, repeated navigation and accessible controls have fresh widget coverage. No new Android startup/native network interruption/physical-device check was performed. Hardware Back behavior remains unresolved: earlier automation sometimes returned to launcher, with no established responsible boundary. Full native TalkBack/focus/contrast/large-text/IME coverage remains NOT VERIFIED; 558 responsive cases and 69 Arabic-font checks are historical harness results, not physical certification.

## 9. Fresh Flutter regression

Executed from mobile/manager_app with the existing SDK, preserving source/tests:

| Command | Fresh result |
| --- | --- |
| dart format --output=none --set-exit-if-changed lib test | 98 files, 0 changed; exit 0 |
| flutter analyze --no-pub | No issues found; exit 0 |
| flutter test --no-pub --reporter json | **699 passed; 0 failures; 0 skips; exit 0** |

Accounting: **699 inherited + 0 new = 699 total**. Twenty-one hidden loader events are excluded from the test total. The successful terminal event was checked. Execution started 2026-10-10T08:23:41Z and finished 08:25:29Z. No expensive live/backend/visual suite was repeated. Fresh flutter --version --machine confirms Flutter 3.47.5 stable / Dart 3.13.4; no toolchain changes were made.

## 10. Database isolation, cleanup and current resources

Historical completion confirms unique owned business/API databases and phase8_ login, collision checks, HAS_DBACCESS(AlMahwarDB)=0 plus an actual denied connection before fixtures, fixture business schema 1.10.0 and API schema 1.0.0. The API targeted only those databases. Genuine authentication changed only fixture user/session state. Fixture business reads retained their fingerprints. Completion used real schema/catalog metadata, not real business rows, and recorded cleanupErrors=[].

**Fresh independent read-only SQL audit at 2026-10-10T08:25:03Z:**

~~~text
DisposableBusinessDatabases=0
DisposableApiDatabases=0
TemporarySqlLogins=0
PermanentApiDatabase=0
BusinessSchema=1.10.0
realBusinessRowsRead=false
mutationsPerformed=false
~~~

Count scope: released disposable patterns AlMahwarApiIT[_]%, AlMahwarApiSessionIT[_]% and Phase 8 temporary login pattern phase8[_]%. Unknown/unrelated resources were not deleted. The permanent AlMahwarApiDB remains absent; therefore its schema was not freshly queried. **API schema 1.0.0 is the historical disposable result**, not a newly provisioned permanent database claim.

The fresh audit selected only master catalog counts and dbo.Schema_Info.schema_version. Existing SQL administrator credentials stayed in private process environment/memory and were cleared afterward. No business DML, fixture provisioning, migration or real business-table read was performed here.

Fresh ADB inventory is empty; previous owned API/emulator PIDs 1724/5028 are absent. Existing SQL/Hadoop/multica containers remain running. No new fixture/process was started, no existing service was restarted or stopped, and no unrelated resource was altered.

## 11. Protected assets, refs and final Git audit

All **613 tracked-file raw SHA-256 hashes** match the Phase 8 entry snapshot. Desktop, Spring Boot API, Shared Core, SQL/schema sources, frozen contract, Flutter production/auth/network/storage, inherited tests, dependencies/lockfiles and Android configuration remain unchanged. Business schema 1.10.0 was freshly queried; disposable API schema 1.0.0 is historical and its source unchanged.

All pre-existing local/origin refs and tag objects, plus actual remote heads/tags/peeled refs, match the baseline. The only additional local ref is the existing Phase 8 branch at its accepted base. Phase 8 has no new commit or remote branch. Representative protected refs:

| Ref | Unchanged SHA |
| --- | --- |
| main / origin/main | c0234e49e2bf00e094f2bf5de68bebd7fe5f963b |
| api-phase5-manager-final / origin counterpart | b86e66eb2fe24f672a73324518813909feca7c62 |
| flutter-manager-phase6-final-features / origin counterpart | 28883bc36d13a9becfe892dbc3f30e9d04bd1704 |
| flutter-manager-phase7-release-readiness / origin counterpart | 911464e19c01c43555d115eef947f155d4bd7dc0 |
| current local Phase 8 HEAD | 911464e19c01c43555d115eef947f155d4bd7dc0 |
| v1.0.0 tag object | 7202640ad2f44ef879920cff2d2b08ab13fb6446 |
| v1.0.1 tag object | 8b97ebb60e333522f784e63935cb2ad2b088c627 |

The complete comparison is recorded in part-b-recovery/boundary-audit.json against the preserved baseline.json, not restricted to these representative rows.

Desktop redesign stash **df78653663bcbfa5c331a83f2a79bc39d4076f52** and the entire stash list are unchanged. No restore/drop/stash operation occurred. Desktop login.fxml/styles.css backup hashes remain intact. Untracked docker retains its original empty-file SHA-256. Historical evidence/archive hashes remain unchanged.

The requested branch/HEAD/status/diff-check/staged-path checks pass; index empty, git diff --check exit 0, no tracked changes. Individual untracked inventory is exactly:

~~~text
?? docker
?? docs/FLUTTER_MANAGER_PHASE8_PART_A_ANDROID_INTEGRATION_REPORT.md
?? docs/FLUTTER_MANAGER_PHASE8_PART_B_SECURITY_REVIEW.md
~~~

## 12. Remaining limitations and production blockers

These remain OPEN / NOT VERIFIED rather than being presented as failures of an unexecuted test:

1. Physical Android device and comprehensive TalkBack/focus/contrast/native large-text/keyboard/hardware Back testing; emulator UWB/System UI reliability.
2. Hardware-backed keystore guarantees, invalidation/lock/biometric/storage-failure edges, reinstall and backup restoration; direct safe deletion verification for the later completion case.
3. Simultaneous native refresh stress, absolute session expiry, explicit live permission reassignment while a protected screen is open, native network interruption and full ambiguous-write native QA. Fresh inherited tests cover the corresponding application safeguards, not complete native stress certification.
4. Production HTTPS endpoint/deployment and separately authorized permanent API security database provisioning, credential management and restricted production database access.
5. Android toolchain licenses/warnings, reproducible supported release build, signing ownership/secure signing material, final metadata/branding and packaging/distribution approval.
6. Backup/restore drills, deployment/rollback operations, production retention/capacity/performance measurement and monitoring/alerting.

Native login, password-change/new-login and representative storage restoration are now supported for the isolated debug environment. They are no longer wholly absent as at Phase 7. Their remaining narrower limits above stay open. No production signed APK/AAB, deployment, certification or release approval is claimed.

No critical exercised security, regression, isolation or source-integrity failure remains. Report recovery and Part B review are complete within the authorized evidence-based scope. **STOP FOR HUMAN REVIEW.**

FLUTTER MANAGER PHASE 8 PART B READY FOR REVIEW

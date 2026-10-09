# Flutter Manager Phase 7 — Part A stabilization report

Date: 2026-10-09. Status: Part A implemented; awaiting the user's Part B testing, security, documentation and final-review requirements. This is not Android or production release certification. No stage, commit, push, merge, rebase, tag, PR, signing or distribution was performed.

## Accepted predecessor and branch gate

- Accepted Phase 6 commit: `28883bc36d13a9becfe892dbc3f30e9d04bd1704`.
- Its parent: `c7510ee3440471681a93b4b882a8861ad19cec8c`.
- One normal approved Phase 6 commit, with exactly 24 paths matching its report, 1 modified and 23 new. Committed-source finalization recorded 662 passing Flutter tests with zero failures/skips.
- Before branching, local Phase 6, origin tracking and actual remote SHA matched; ahead/behind was 0/0. Index was empty and status contained only unrelated untracked `docker`.
- Created `flutter-manager-phase7-release-readiness` from the verified full SHA above. Current HEAD remains that SHA. Phase 7 is uncommitted and unpublished.

The initial audit was written before production fixes at ignored `target/flutter-phase7/initial-audit.md`. It retains the initial failing measurements and subsequent confirmed findings. The initial snapshot, ignored `target/flutter-phase7/baseline.json`, records all 608 tracked file hashes, local/origin refs, tags, actual remote refs, stash and Docker hash.

## Confirmed defects and bounded fixes

| ID | Evidence before correction | Correction and validation |
| --- | --- | --- |
| P7-001 | Shared error/retry row at 320px and text scale 2.0 overflowed by 202px, leaving the message with width 0. | Wrap the safe message, place retry below it, keep intrinsic height and the existing live announcement. Regression confirms readable width, bounded height, reachable retry and Android tap-target guideline. |
| P7-002 | Profile initial load plus two concurrent refreshes made three `GET auth/sessions` calls while the response was pending. | Share one pending own-session read. Account/session generation checks reject stale completions; an auth listener clears private state on account/session loss and is removed on disposal. The same probe now measures one request. Account-switch and disposed-screen regressions pass. |
| P7-003 | Paused app still made one periodic `GET auth/me` request after 61 seconds. | Suspend the presentation timer outside resumed lifecycle; on resume use the existing session check and restart foreground monitoring. Paused probes measure zero requests; resume makes one validation and the next foreground interval makes one. Existing refresh-token protocol is unchanged. |
| P7-004 | Existing actual-font admin screenshot visually reordered `2026-10-07T12:00:00` into `07T12:00:00-10-2026` inside RTL. Similar raw interpolations existed in quotations, Reports and Profile. | Reuse the existing `isolateDate` presentation helper for these labels. Preserve exact source values, existing timezone handling and accounting dates. Widget checks and Arabic-font images show correct date order. |
| P7-005 | Profile current-device row at 320px overflowed by 36px at scale 1.5 and 102px at scale 2.0. | Place the existing current-device badge below the icon/name row. Keep the requested text scale and readable wrapping. Responsive matrix and real-font images pass. |
| P7-006 | Admin and generic confirmation content at 320px/scale 2.0 clipped a contract-sized identity and warning. Last warning glyphs were at y=1393.98 while the paragraph had only 32px height; there was no scroll container. | Enable existing AlertDialog scrolling. Identity, warning and actions remain readable and accessible. Cancellation sends zero writes. Existing auth-scope approval, double-submit and uncertain-write protections are retained. |

The confirmation fixture uses a 98-character full name and 50-character username, within published limits. Initial behavioral probes used synthetic fixtures and failed before fixes; the corrected probes pass. No new business endpoint, caching layer, business behavior or redesigned screen was added. Performance claims are limited to the measured request reductions above; no battery, frame-rate or memory benchmark is claimed.

## Exact reviewable file manifest

Seven modified Flutter presentation files and three new test/documentation files:

| Change | Path |
| --- | --- |
| Modified | mobile/manager_app/lib/app/manager_app.dart |
| Modified | mobile/manager_app/lib/shared/widgets/states.dart |
| Modified | mobile/manager_app/lib/features/profile/presentation/profile_screen.dart |
| Modified | mobile/manager_app/lib/features/administration/presentation/admin_screen.dart |
| Modified | mobile/manager_app/lib/features/administration/presentation/admin_confirmation.dart |
| Modified | mobile/manager_app/lib/features/quotations/presentation/quotation_screen.dart |
| Modified | mobile/manager_app/lib/features/reports/presentation/report_screen.dart |
| New | mobile/manager_app/test/phase7_readiness_test.dart |
| New | mobile/manager_app/test/phase7_responsive_test.dart |
| New | docs/FLUTTER_MANAGER_PHASE7_PART_A_REPORT.md |

`docker` is excluded and untouched. Audit snapshots, helpers, logs and screenshots remain ignored under `target/flutter-phase7/` or `mobile/manager_app/build/`. Gradle generated an untracked Android `.kotlin/errors` log; after the failed build it was safely moved into ignored `target/flutter-phase7/android-kotlin-build-log/`. No legitimate platform file was removed or modified. No generated artifact is an intended review/commit file. Staging index remains empty.

## Fresh Part A verification

Flutter 3.47.5 / Dart 3.13.4 from the existing SDK.

| Check | Fresh result |
| --- | --- |
| `dart format --output=none --set-exit-if-changed lib test` | 98 files checked, 0 changed, exit 0 |
| `flutter analyze --no-pub` | No issues found, exit 0 |
| `flutter test --no-pub` | **699 passed: 662 inherited + 37 new; 0 failures / 0 skips**, exit 0 |
| `git diff --check` | Passed |
| Protected source hashes / refs / tags / stash / backups / Docker | Final comparison recorded in ignored `final-boundaries.json` |

New tests: 13 request/lifecycle/date/navigation/accessibility regressions plus 24 responsive/form/confirmation tests. The 18 responsive tests each exercise 31 screens: **558 screen/viewport/scale/theme combinations**. Viewports are 320x700, 390x844 and 800x1024; scales 1.0, 1.5 and 2.0; themes light and dark. Checks visit initial and scrolled states without submitting writes. Four form checks exercise keyboard insets and reachable fields at 320px/scale 2.0. Two dialog checks verify unclipped complete warnings, scrolling and cancellation.

Screen coverage includes login, password change, Profile/sessions, Dashboard, products, low stock, product detail, movements, sales, invoice list/detail, customers/suppliers and both detail/account statements, quotations/list detail, Reports index, expenses, cash, daily, inventory summary, slow stock, Audit, users/list detail and create/edit/reset forms.

Two consecutive traversals of all ten supported drawer destinations retain one root route. Logout removes a pushed protected route. Fresh inherited tests cover restricted password flow and Android Back dispatch in the widget harness, terminal revocation/expiry, shared 401 refresh, permission and role loss, redaction in text/semantics, stale responses, pagination single-flight/retry/deduplication and resource disposal. This is Flutter harness evidence, not a native-device assertion.

A separate ignored visual harness loaded actual Arabic-capable Windows fonts and Material icons. It completed 63 checks across seven representative views, all three sizes/scales and both themes, plus six narrow/tablet confirmation checks. The affected error views were rerun after the final intrinsic-height correction. **69 distinct actual-font checks / 132 theme render cases**, with no render failures; 264 top/body PNGs remain ignored. Representative narrow/tablet, light/dark login, Profile, user date, quotation date/large amounts, daily/cash, error and confirmation images were visually inspected. Monetary strings retain exact three decimals. Existing long-value fitting remains unchanged; no new shrinking policy was introduced.

Error live-region and retry tap targets were checked; obscured login/password fields retain existing visibility controls. Existing financial semantics remain permission-gated. Formal TalkBack, physical-device contrast/focus and native keyboard certification remain pending native execution and Part B.

## Security, architecture and network boundaries

All 601 baseline tracked files outside the seven allowed presentation paths are protected by raw SHA-256 comparison. This includes inherited tests, auth controller/repository/client/vault, Android/iOS configuration, dependencies/lockfile, Desktop, API, Shared Core, SQL, schemas, frozen contracts and historical reports.

Production source scans check raw JWT/refresh/GitHub-token patterns, private keys, literal passwords, direct SQL and response logging. Changed presentation files introduce no floating-point financial conversion. Existing money uses exact decimal strings/BigInt; dates are only directionally isolated. Business reads remain frozen GET requests, and the only business-administration writes remain the five released create/edit/disable/enable/reset operations. No permanent user deletion, quotation/POS mutation or arbitrary permission editor was added.

Fresh inherited transport/auth tests exercise connect/receive timeout, network loss and certificate errors, malformed/empty responses, safe HTTP 401/403/404/429/500/503 messages, refresh limits, recovery/retry and logout cleanup. No raw backend message is displayed. Admin cancellation, secret-field clearing, last-admin rejection, no optimistic success and no automatic repetition of uncertain writes remain covered. New session polling changes do not change authenticated retry rules.

The current task created no databases or SQL logins and performed no SQL/business mutations. Historical Phase 6 isolated verification recorded business schema **1.10.0**, disposable API schema **1.0.0**, nine live API tests and zero remaining disposable databases/logins. Those live tests, the old 42 render checks, API 637 and Desktop 373 discovered /168 executed /205 skipped are historical results; API/Desktop/SQL/live suites were **not freshly rerun** in Part A. Schema source hashes remain unchanged; no new live schema query or production connectivity claim is made.

## Android attempt and exact blockers

Fresh `flutter doctor -v`, `flutter devices` and `flutter emulators` were run. Doctor sees SDK 36/build-tools 36, existing Pixel_7 AVD and Android Studio's OpenJDK 25.0.3. It reports unaccepted Android licenses and missing Windows C++ components; Windows desktop compilation is outside this Android task. No license was accepted by this task. Gradle resolved/installed existing Platform 35 using its already accepted license during the requested build attempt; no project dependency or tracked platform file changed.

Attempted from the existing project:

```text
flutter build apk --debug --no-pub --dart-define=API_BASE_URL=https://example.invalid
```

**Build failed, exit 1.** Failing task `:app:processDebugMainManifest` reports a manifest-merger conflict: debug `android:usesCleartextTraffic=true` versus main `false`. The log also records Kotlin compiler-daemon connection failures. The primary manifest failure is an established native-build blocker. Part A explicitly requests no permanent platform changes, so neither manifest was altered. A future reviewed debug-overlay correction must retain production cleartext prohibition; this report does not implement that correction.

The existing Pixel_7 emulator was launched headless/read-only with no snapshot saves on serial `emulator-5556`. ADB observed `sys.boot_completed=1` and Android 17. The failed build prevented installing or starting a current-source APK. Thus native app startup, native Arabic login, authentication, rotation/back/lifecycle and emulator-to-host API networking are **not certified**. Widget-rendered Arabic login and widget lifecycle checks are distinct results. The exact owned emulator was subsequently stopped; ADB lists no remaining devices. No user device/AVD was replaced or deleted.

## API configuration and release preparation

`https://example.invalid` was an explicitly unreachable, non-production configuration probe. It is not a development or production service. No new disposable API was provisioned. Prior disposable live verification is documented separately. No configured production endpoint or provisioned permanent API security database was used; production login has not been tested or claimed.

Existing AppConfig requires `API_BASE_URL`, normalizes only the released API prefix and rejects credentials/query/fragment/unexpected paths. HTTPS is required. Private-host HTTP additionally requires explicit `ALLOW_INSECURE_HTTP=true` in debug; release rejects it. Existing debug manifest permission remains isolated, but currently causes the merger failure above. No TLS validation, release cleartext policy or credential defaults were weakened.

Inspected release metadata: namespace/application ID `com.almahwar.manager_app`, Arabic display name `مساحة الإدارة`, version `0.1.0+1`, min SDK `max(23, flutter.minSdkVersion)`, compile/target SDK from Flutter, Java/Kotlin target 17, Internet permission, backup disabled, stock launcher assets and no release signing configuration. Five Android launcher PNGs total 4,181 bytes; pubspec declares no custom image/font assets. APK size and reproducibility cannot be certified because the current debug build failed. There is no final commercial branding decision or production artifact.

Release prerequisites remain:

1. Review/fix the debug manifest merge and investigate compiler-daemon/toolchain warnings, resolve remaining Android licenses, then obtain a reproducible successful native build.
2. Supply an authorized development/disposable API environment for actual Android authentication/network/lifecycle testing; separately plan permanent security DB and production HTTPS deployment outside this task.
3. Complete Part B's testing/security/docs/review requirements and native accessibility/device checks.
4. Decide release version/identity/assets/signing ownership and distribution procedure in a later authorized release task. No signing secret, keystore or production endpoint is introduced here.

## Protected Git artifacts and stop condition

All pre-existing local/origin branches and release tags are compared to the initial snapshot, including actual remote heads/tags; only the new local Phase 7 branch is additional. Desktop redesign stash `df78653663bcbfa5c331a83f2a79bc39d4076f52` and independent `20261008-3d7c4ed8` login.fxml/styles.css backups retain their original hashes. Stash was not restored/dropped/committed. Docker retains its original empty-file hash and remains untracked.

Verified exact Part A final status:

```text
 M mobile/manager_app/lib/app/manager_app.dart
 M mobile/manager_app/lib/features/administration/presentation/admin_confirmation.dart
 M mobile/manager_app/lib/features/administration/presentation/admin_screen.dart
 M mobile/manager_app/lib/features/profile/presentation/profile_screen.dart
 M mobile/manager_app/lib/features/quotations/presentation/quotation_screen.dart
 M mobile/manager_app/lib/features/reports/presentation/report_screen.dart
 M mobile/manager_app/lib/shared/widgets/states.dart
?? docker
?? docs/FLUTTER_MANAGER_PHASE7_PART_A_REPORT.md
?? mobile/manager_app/test/phase7_readiness_test.dart
?? mobile/manager_app/test/phase7_responsive_test.dart
```

All work remains uncommitted. Await Part B; do not initiate release packaging or finalization.

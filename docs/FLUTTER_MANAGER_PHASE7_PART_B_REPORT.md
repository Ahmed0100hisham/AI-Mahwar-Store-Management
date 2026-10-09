# Flutter Manager Phase 7 — Part B verification report

Date: 2026-10-09. Status: the Android debug manifest blocker is corrected and the received verification requirements have been exercised within the limits below. Human acceptance and full Part B completion are pending. No staging, commit, push, merge, rebase, tag, PR or production release packaging was performed.

The received Part B message stops at section 6, after **“Verify controller disposal and single”**. The remaining text was requested; it has not been supplied. This report does not invent the missing requirements or a final approval statement.

## Preserved baseline

- Branch: `flutter-manager-phase7-release-readiness`.
- Unchanged HEAD: `28883bc36d13a9becfe892dbc3f30e9d04bd1704`.
- The ten accepted Part A files remain byte-for-byte unchanged against the Part B entry snapshot: seven modified presentation files, two new test files and the Part A report. No work was reset, discarded, stashed or restarted.
- Accepted Part A coverage is 699 tests, 558 responsive cases and 69 distinct Arabic-font checks. These checks were executed again in Part B; results appear below.
- Staging index remains empty. Untracked `docker` and Desktop redesign stash `df78653663bcbfa5c331a83f2a79bc39d4076f52` remain untouched.

## Android blocker and smallest correction

The main manifest declares `android:usesCleartextTraffic="false"`. The existing debug overlay already declares the same attribute as `true`. Android's manifest merger cannot resolve these conflicting explicit values without an override marker; the failing task was `:app:processDebugMainManifest`.

Only `mobile/manager_app/android/app/src/debug/AndroidManifest.xml` was changed in Part B production/platform source:

1. Add the `xmlns:tools="http://schemas.android.com/tools"` namespace.
2. Add `tools:replace="android:usesCleartextTraffic"` to the existing debug `<application>` element.

The main and profile source manifests, Gradle configuration, application ID, dependencies, API configuration, backend and frozen contract are unchanged. No globally permissive manifest, network-security exception, TLS bypass or release HTTP allowance was introduced.

Fresh merged-manifest processing succeeded for all three variants:

| Variant | Merged `usesCleartextTraffic` | Merged `allowBackup` |
| --- | --- | --- |
| Debug | true | false |
| Profile | false | false |
| Release | false | false |

Release/profile manifest processing verifies the policy without building or distributing a release APK. Merger outputs and decision reports are generated under ignored `mobile/manager_app/build/`.

The existing `AppConfig` still requires a supplied API address and HTTPS. Private-host HTTP additionally requires explicit debug `ALLOW_INSECURE_HTTP=true`; profile/release reject HTTP. Credential/query/fragment/unexpected-path rejection remains unchanged and covered by inherited tests. `API_BASE_URL` and `ALLOW_INSECURE_HTTP` were not configured in the task environment, and no suitable local development API listener or approved test credentials were available.

## Fresh Android build and native smoke

Fresh `flutter doctor -v` and `flutter devices` completed. Flutter is 3.47.5, Dart 3.13.4, Android SDK/build-tools 36, with the existing Android Studio JBR 25.0.3. Doctor reports unaccepted Android licenses and missing Windows C++ components. The task accepted no new license. Gradle resolved CMake 3.22.1 using its already accepted license; tracked dependencies and SDK configuration were not changed.

| Command/check | Fresh result |
| --- | --- |
| `flutter build apk --debug` | Passed, exit 0, approximately 389.8 seconds; no API define supplied |
| `:app:processReleaseMainManifest :app:processProfileMainManifest` | Passed, exit 0; both forbid cleartext |
| Final `flutter run --debug --no-pub --no-resident -d emulator-5556 --dart-define=API_BASE_URL=https://example.invalid` | Passed, exit 0; APK built, installed and Flutter started on Android |

The placeholder HTTPS address is deliberately unreachable and is not a production or development service. It permits observing startup without using credentials. No login request was submitted with credentials and no authenticated API or business operation was exercised.

Final debug APK: `mobile/manager_app/build/app/outputs/flutter-apk/app-debug.apk`, 161,479,795 bytes. SHA-256: `1B7CDE29ECD6C25ADAD1E255F396D527810D765C2F7FB05BC5244D1F1E6121CB`. This is a debug smoke artifact containing the placeholder define, not a production release or a release-size measurement.

The existing Pixel_7 Android 17/API 37.2 AVD was launched read-only, headless and without saving snapshots on owned serial `emulator-5556`. The final run retained the AVD's existing memory/core defaults. ADB confirmed completed boot and the actual application process/foreground activity. Observed checks:

- Real Arabic login, RTL labels and password obscuring appeared on Android in light and dark system modes.
- Empty login submission displayed Arabic username/password validation. No credentials or API login were used.
- The empty password visibility control changed its Arabic accessibility label between show/hide.
- The native keyboard opened for the username field; the resized screen retained both fields and the login button. Back dismissed input/backgrounded the root activity; the launcher became foreground.
- Home/background followed by bringing the app forward retained the Arabic login screen. This is an unauthenticated lifecycle check; authenticated resume and refresh are separately covered in the Flutter harness.
- Application PID 5277 had no fatal/unhandled matches in the inspected app log. The Android crash buffer contained no application name or fatal entry for that PID.

The emulator environment was unstable: System UI showed an ANR during cold boot and recovered after selecting Wait. The global crash buffer also records repeated `/vendor/bin/hw/android.hardware.uwb-service` aborts, rather than application crashes. A reduced-memory preliminary attempt was abandoned; an earlier Flutter run built successfully but failed during installation when the owned emulator was stopped. That failed attempt is retained alongside the successful final retry. These results do not establish physical-device stability, performance, TalkBack certification, authenticated connectivity or secure-storage write/rotation certification.

JDK native-access/Gradle deprecation warnings and Kotlin compiler-daemon connection failures remain visible; fallback compilation succeeded. Resolve the toolchain/license/emulator issues in a later authorized release-readiness step. No unrelated Gradle/dependency refactor was performed here.

Only the owned emulator was stopped after smoke checks. Final `adb devices` shows no devices; the existing AVD was not replaced, deleted or saved over.

## Fresh Flutter regression, responsiveness and accessibility

| Check | Fresh Part B result |
| --- | --- |
| `dart format --output=none --set-exit-if-changed lib test` | 98 files checked, 0 changed, exit 0 |
| `flutter analyze --no-pub` | No issues found, exit 0 |
| `flutter test --no-pub` | **699 inherited + 0 new Part B = 699 passed; 0 failures / 0 skips**, exit 0 |
| Actual Arabic-font visual harness | **69 distinct checks passed**, 0 failures / 0 skips |
| `git diff --check` | Passed |

No additional Dart behavior changed in Part B, so no inherited test was weakened and no redundant Dart test was added. The Android correction was verified by the successful APK build and actual merged debug/profile/release manifests.

The freshly executed inherited matrix visits 31 Phase 1–6 screens across 320x700, 390x844 and 800x1024, scales 1.0/1.5/2.0, light/dark: **558 screen combinations**. Login/password/Profile, all feature lists/details/accounts/reports, Audit, user administration and create/edit/reset forms are included. It checks initial/scrolled states and catches Flutter layout errors. Four keyboard-inset form checks and two long scrollable confirmation checks also pass. Drawer traversal, root-route behavior, logout route cleanup, error announcement/tap target, financial semantics and secret-field tests remain green.

The separate actual-font harness reran 69 distinct checks /132 theme cases and produced 264 top/body PNGs. Representative images were inspected. Real-font login, Profile/device badge, dates, money, error and confirmation views retain readable Arabic/RTL presentation without observed overflow or clipping. These images use installed Arabic-capable desktop fonts; they complement the native Android login evidence, not an exhaustive native/TalkBack certification of protected screens.

## Security and network resilience evidence

The complete fresh 699-test run includes the following inherited protections. These are fixture-based unit/widget/transport checks, not fresh backend integration claims:

| Area | Verified evidence |
| --- | --- |
| Refresh/revocation | Shared simultaneous 401 refresh, one retry limit, terminal revoked/expired refresh cleanup, stale rotation cannot resurrect logout or replace another account |
| Lifecycle/navigation | Paused polling makes zero requests; resume validates once and restarts foreground checks; live permissions gate drawer destinations; logout removes protected routes |
| Financial privacy | Cost/profit/balance redaction in models, text and accessibility semantics; denied balances are not fabricated as zero; exact decimal strings/BigInt remain unchanged |
| Audit/private data | Admin role plus live audit permission required; unknown/raw payload remains neutral; returned password/hash/session-private fields are discarded |
| Password handling | Obscured fields, cancel sends no secret, permission loss clears forms; no password persistence/response logging was introduced |
| Admin writes | Only the five released create/edit/disable/enable/reset operations; explicit confirmations; one in-flight write; uncertain timeout/503/malformed completion blocks repetition until reread and acknowledgement; safe last-admin rejection |
| Network errors | Slow/pending requests, connect/receive timeout, network loss, certificate failure, safe 401/403/404/429/500/503, malformed responses, controlled retry and stale-response rejection |
| Disposal/concurrency | Disposed profile/list controllers ignore pending reads; search timer disposal cancels callbacks; shared refresh and pagination single-flight, independent child pages and deduplication remain green |

Production Dart scans found no raw JWT/refresh/GitHub tokens, private keys, literal passwords, direct SQL access or response logging. No new floating-point financial conversion was introduced. The debug marker adds no business/POS/quotation mutation or backend endpoint. No new dependency, migration or auth protocol was added.

All 600 baseline tracked files outside the eight allowed modified paths retain their raw SHA-256 hashes. This protects main/profile manifests, auth/storage/network, dependencies/locks, inherited tests, Desktop/API/Core/SQL/schema sources and frozen contracts. All ten accepted Part A files also retain their Part B entry hashes.

No SQL command, database provisioning, temporary login creation or real business mutation was performed in Part B. Historical isolated Phase 6 evidence records business schema 1.10.0, disposable API schema 1.0.0, nine live tests and zero remaining disposable databases/logins. The permanent API security database was not provisioned and remains outside this task. Schema sources are unchanged; no fresh live schema or server-wide cleanup query is claimed. API 637 and Desktop 373 discovered /168 executed /205 skipped are historical accepted baselines, not freshly rerun suites.

## Exact reviewable manifest and preserved Git boundaries

Combined Part A + Part B: **12 files = 8 modified + 4 new**. Part B adds only the debug manifest correction and this report.

| Change | Path |
| --- | --- |
| Modified, Part B | mobile/manager_app/android/app/src/debug/AndroidManifest.xml |
| Modified, preserved Part A | mobile/manager_app/lib/app/manager_app.dart |
| Modified, preserved Part A | mobile/manager_app/lib/shared/widgets/states.dart |
| Modified, preserved Part A | mobile/manager_app/lib/features/profile/presentation/profile_screen.dart |
| Modified, preserved Part A | mobile/manager_app/lib/features/administration/presentation/admin_screen.dart |
| Modified, preserved Part A | mobile/manager_app/lib/features/administration/presentation/admin_confirmation.dart |
| Modified, preserved Part A | mobile/manager_app/lib/features/quotations/presentation/quotation_screen.dart |
| Modified, preserved Part A | mobile/manager_app/lib/features/reports/presentation/report_screen.dart |
| New, preserved Part A | mobile/manager_app/test/phase7_readiness_test.dart |
| New, preserved Part A | mobile/manager_app/test/phase7_responsive_test.dart |
| New, preserved Part A | docs/FLUTTER_MANAGER_PHASE7_PART_A_REPORT.md |
| New, Part B | docs/FLUTTER_MANAGER_PHASE7_PART_B_REPORT.md |

All pre-existing local/origin branches, release tags and actual remote heads/tags are compared against the Part A baseline. The only additional branch remains the local Phase 7 branch. Desktop stash and independent `20261008-3d7c4ed8` login.fxml/styles.css backups retain their hashes; neither Desktop file was restored or added to Phase 7. Docker retains its original empty-file hash and is untracked. Index remains empty. Helpers, APKs, XML dumps, logs and screenshots remain ignored under `target/flutter-phase7/` or `mobile/manager_app/build/`.

Exact final `git status --short`:

```text
 M mobile/manager_app/android/app/src/debug/AndroidManifest.xml
 M mobile/manager_app/lib/app/manager_app.dart
 M mobile/manager_app/lib/features/administration/presentation/admin_confirmation.dart
 M mobile/manager_app/lib/features/administration/presentation/admin_screen.dart
 M mobile/manager_app/lib/features/profile/presentation/profile_screen.dart
 M mobile/manager_app/lib/features/quotations/presentation/quotation_screen.dart
 M mobile/manager_app/lib/features/reports/presentation/report_screen.dart
 M mobile/manager_app/lib/shared/widgets/states.dart
?? docker
?? docs/FLUTTER_MANAGER_PHASE7_PART_A_REPORT.md
?? docs/FLUTTER_MANAGER_PHASE7_PART_B_REPORT.md
?? mobile/manager_app/test/phase7_readiness_test.dart
?? mobile/manager_app/test/phase7_responsive_test.dart
```

## Evidence and pending human review

Ignored evidence includes `part-b-format.log`, `part-b-analyze.log`, `part-b-tests.jsonl`/summary, `part-b-arabic-font.jsonl`/summary, doctor/devices logs, debug/variant build logs, merged-manifest assertions, failed/final native-run logs, APK hash, emulator ownership/cleanup, crash buffer and final `part-b-boundaries.json`.

Native review images: `target/flutter-phase7/part-b-login-ready.png`, `part-b-validation.png`, `part-b-dark-login.png` and `part-b-keyboard-retry.png`; XML dumps retain observed Arabic accessibility labels. The crash/ANR evidence is retained as well.

Pending: supply the remainder of section 6 and subsequent Part B requirements; obtain human visual/security acceptance; use a separately authorized isolated API and approved test credentials for native authentication/refresh/revocation/secure-storage testing; perform physical-device/TalkBack checks and resolve emulator/toolchain warnings before any release certification. All Phase 7 work remains uncommitted and unpublished. Stop for review; do not commit or push.

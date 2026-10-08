# Manager Flutter Phase 1 — foundation and authentication

The application lives in `mobile/manager_app`. It starts from accepted API Phase 5 commit `b86e66eb2fe24f672a73324518813909feca7c62` and consumes the frozen `API_MANAGER_CONTRACT_V1.md`. Desktop, released Shared Core 1.0.1, API behavior and both database schemas are unchanged. The temporary UI title is **مساحة الإدارة**; the existing `com.almahwar` native namespace is preserved. No rebranding or custom logo is introduced; launcher artwork is Flutter-generated template artwork.

## Toolchain and setup

Verified with installed **Flutter 3.47.5 stable**, framework `6a19cca56475dbfba1478ee68d7bd0c2ef891da1`, **Dart 3.13.4**. Android and Windows scaffolds are generated. No iOS/web application target is generated. `pubspec.lock` fixes the resolved application dependency graph.

From the repository root:

```powershell
cd mobile/manager_app
flutter pub get
flutter analyze
flutter test
flutter devices
flutter run -d <android-device-id> --dart-define=API_BASE_URL=https://your-api-host
```

`API_BASE_URL` accepts an HTTPS origin or that origin followed by `/api/v1`. It has no production default and rejects embedded credentials, query strings, fragments and other path prefixes. Missing/invalid configuration displays a clear Arabic setup error and creates no network/storage client. Nothing here configures a backend, database or signing secret.

For an Android emulator connected to an explicitly local disposable/development service:

```powershell
flutter run -d <emulator-id> --dart-define=API_BASE_URL=http://10.0.2.2:8085 --dart-define=ALLOW_INSECURE_HTTP=true
```

Use the actual API port. HTTP requires a debug build, explicit opt-in and a private/loopback host; release/profile configurations require HTTPS. Android permits cleartext only in its debug manifest; the main manifest explicitly forbids it. Release/profile deployment needs trusted HTTPS. Certificate verification is never bypassed by Dart. A physical device cannot reach the development machine through its own `localhost`; use an appropriately secured reachable API endpoint. Native local-network permissions/policies must also be checked on the target Android OS before LAN testing.

Windows, after installing its native build prerequisites:

```powershell
flutter run -d windows --dart-define=API_BASE_URL=https://your-api-host
```

This workstation currently has unaccepted Android SDK licenses, no attached Android device, missing Visual C++/CMake/Windows SDK components, and no enabled Windows developer symlink support. No settings/licenses/toolchains were changed. Native APK/Windows builds and real-device keystore verification are outstanding; analyze, VM/widget tests and live HTTP contract checks run successfully. Production signing is deliberately unconfigured; no release is produced or signed with debug credentials.

## Structure and dependencies

```text
lib/
  app/                         Composition, Arabic locale, session route guard/lifecycle
  core/config/                 Validated environment configuration
  core/network/                Transport and one-retry authenticated API client
  core/errors/                 Safe Arabic failures
  core/storage/                Secure opaque-refresh storage abstraction
  core/routing/                Permission-driven future destinations
  core/theme/                  Material 3 light/dark themes and spacing
  core/utils/                  Exact decimal-string KWD formatting
  features/auth/data/          Frozen-contract DTOs and authentication repository
  features/auth/state/         AuthController and session lifecycle
  features/auth/presentation/  Login and own-password change
  features/profile/presentation/ Own profile and devices
  features/dashboard/presentation/ Authenticated shell only; no dashboard data
  shared/widgets/              Inputs, loading/empty/error states, confirmation dialogs
```

UI uses `ChangeNotifier`/`ListenableBuilder` from Flutter. No state-management, router, code-generation, ORM or database package is added. Direct runtime dependencies are Flutter, `flutter_localizations` (SDK), **Dio 5.11.1**, and **flutter_secure_storage 11.2.0**. Development dependencies are SDK `flutter_test` and `flutter_lints 6.0.0`. Platform storage packages and `intl` are transitive dependencies. Native namespaces exist only in platform scaffolding; reusable code has no customer/product branding.

## Exact API integration

All paths below are relative to `/api/v1` and match the frozen implementation:

| Operation | Request/response |
|---|---|
| POST `/auth/login` | username, password, deviceLabel → LoginResponse |
| POST `/auth/refresh` | refreshToken → rotated LoginResponse |
| GET `/auth/me` | CurrentUserResponse |
| POST `/auth/logout` | 204 |
| POST `/auth/logout-all` | 204 |
| POST `/auth/change-password` | currentPassword, newPassword, confirmPassword → 204 |
| GET `/auth/sessions` | SessionView array |
| DELETE `/auth/sessions/{sid}` | 204; own-session scope |

LoginResponse reads actual `accessToken`, `tokenType`, `expiresIn`, `expiresAt`, `mustChangePassword`, `user`, optional `refreshToken` and `sid`. Refresh/absolute expiry fields remain server-owned; the client does not replace server session validation with local expiry assumptions. CurrentUserResponse is `{id,username,fullName,roleCode,roleName,mustChangePassword,permissions}`. SessionView reads `{sid,createdAt,lastActivityAt,idleExpiresAt,absoluteExpiresAt,current,deviceLabel,status}`. Current-session identification uses server `current`; no JWT decoding or guessed device matching. Unknown permission codes are retained, and unknown session statuses display a neutral label.

## Authentication, storage and concurrency

Access tokens stay only in memory. Only the opaque refresh credential is persisted through `flutter_secure_storage`, under a key scoped to the complete normalized API URL. Usernames/passwords/access tokens/JWT signing material are not persisted by the application. There is no plain-preferences fallback. Secure-storage failures fail closed and show an Arabic error. Storage writes/deletes are serialized so a pending rotation cannot restore credentials after logout.

The plugin's Android defaults use RSA OAEP key wrapping and AES-GCM; Android minimum SDK is at least 23 and application backup is disabled. Windows uses the plugin's encrypted local storage with a key protected by Windows Credential Manager. These are platform-provided protections, not immunity against a compromised/unlocked OS. Native keystore/credential-manager behavior is still subject to device testing. Tests mock the plugin store and verify origin isolation/deletion ordering; live HTTP checks use an in-memory test vault to avoid persisting test credentials. See the [plugin documentation](https://pub.dev/packages/flutter_secure_storage) and [Windows implementation](https://pub.dev/packages/flutter_secure_storage_windows).

Startup reads the secure refresh credential, rotates it through the real API, persists the replacement before accepting it, then validates `/me`. No cached user profile grants access. Offline/timeout failures do not destroy a valid stored refresh credential; an explicit retry can restore the session. Restricted logins have no refresh credential and cannot survive an app restart.

The centralized client attaches bearer access per request. A protected 401 starts **one shared refresh Future**; simultaneous failures join it. A late 401 for a superseded access token reuses the replacement without replaying the old refresh credential. After successful refresh the original request is retried once. A second 401 or terminal refresh rejection erases credentials and returns to login. Login/refresh never recursively enter the protected retry path. Session epochs discard late responses from logged-out/replaced accounts; old in-flight completion cannot clear a new account's refresh guard or resurrect it. No automatic retries occur on 403 or network failures.

Logout and logout-all always clear local memory and secure storage, including when the network fails. An offline server-revocation failure is shown on the login screen: local sign-out cannot promise remote revocation without connectivity. Secure-storage deletion errors are reported rather than silently claiming that the OS store was cleared. On success the API invalidates the requested server sessions.

Must-change users are routed exclusively to Change Password; normal Manager navigation and back navigation are blocked. The server owns password policy. A successful change invalidates all sessions and local credentials and returns to login; the user must authenticate with the new password. The client never upgrades a restricted token locally.

`/me` is validated after login/restore, profile refresh, app resume and every 60 seconds while the application runs. Refresh responses also carry current user permissions. Disabled/reset/revoked users fail server validation and are signed out after terminal refresh rejection. Role/permission changes update the live user response. Future navigation checks permission codes, including required permission intersections, never `role == ADMIN`. Hiding a destination is only a UI convenience; every real request remains server-authorized and a 403 is preserved.

## Network, UI and precision

Dio has 10-second connect, 15-second send and 20-second receive timeouts. Cross-host redirects and arbitrary absolute request paths are forbidden. No response cache or request/response logging interceptor exists. Requests send `Cache-Control: no-store`; authentication/Manager responses are not persisted. Failures map known codes/statuses to safe Arabic messages rather than displaying arbitrary backend/proxy text. Covered categories include connection, timeout, certificate failure, 400/401/403/404/409/423/429/5xx and malformed responses. No SQL exception, stack trace, token or raw Authorization header is shown or logged.

Arabic locale/localizations provide RTL. Material 3 light and system dark themes, responsive/scrollable forms, OS Arabic font fallbacks, password visibility controls, keyboard submission, busy/validation/error states, confirmations and own-session metadata are reusable foundations. Default launcher icons are generated scaffolding, not new branding. Future module entries are disabled placeholders filtered by permissions; no metrics, dashboard API calls or fake business data are supplied.

`formatKwd` accepts exact three-decimal strings, groups the integer portion and appends د.ك without parsing through `double`. Money/business rules remain on the backend. Security session dates are UTC instants displayed in the device's local time; business-date conversions are outside this phase.

## Verification and limits

Normal `flutter test` discovers only `*_test.dart` and requires no credentials or live services. The explicit `test/live_api_contract.dart` runs separately against a disposable API fixture and requires process-environment names `MANAGER_TEST_API_URL`, `MANAGER_TEST_USERNAME`, `MANAGER_TEST_PASSWORD`, `MANAGER_TEST_RESTRICTED_USERNAME`, `MANAGER_TEST_NEW_PASSWORD`. Do not put values into files, arguments, launch configurations or logs. Provisioning/cleanup belongs outside Flutter; this client contains no SQL setup or database driver. The recorded run used the unchanged packaged backend, distinct disposable catalogs and a temporary non-sa fixture login, all cleaned afterward.

```powershell
flutter analyze
flutter test
# Only with externally supplied disposable fixture environment:
flutter test test/live_api_contract.dart --no-pub
```

Full backend regression was rerun from unchanged source. Detailed results and exact files are in `FLUTTER_MANAGER_PHASE1_REPORT.md`. No frozen-contract defect was discovered. Production HTTPS/provisioning, native signing/device QA and later Manager screens remain separate work; no API/Desktop workaround was introduced.

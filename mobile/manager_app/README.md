# Manager application — Phase 1

Arabic-first Flutter foundation, authentication, own-password change and own devices.
Flutter 3.47.5 stable / Dart 3.13.4. Targets: Android and Windows scaffolding.

```powershell
flutter pub get
flutter analyze
flutter test
flutter run -d <android-device-id> --dart-define=API_BASE_URL=https://your-api-host
```

API_BASE_URL is mandatory; no production default or embedded credentials. An origin
or origin plus `/api/v1` is accepted. Only explicit debug/private-host development
can use HTTP with `--dart-define=ALLOW_INSECURE_HTTP=true`; prefer trusted HTTPS.

Read [architecture and setup](../../docs/FLUTTER_MANAGER_PHASE1_ARCHITECTURE.md),
[verification report](../../docs/FLUTTER_MANAGER_PHASE1_REPORT.md), and the
[frozen backend contract](../../docs/API_MANAGER_CONTRACT_V1.md).

Only refresh credentials enter platform secure storage; access remains in memory.
The backend owns permissions, session validity and password policy. Manager modules,
rebranding, native signing and deployment are outside this foundation phase.

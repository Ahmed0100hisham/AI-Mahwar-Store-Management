# Flutter Manager Phase 2 — real dashboard review report

Date: 2026-10-08. Status: implemented and verified, **uncommitted and unpushed**, ready for review.

## Baseline and scope

- Branch: `flutter-manager-phase2-dashboard`.
- Starting accepted Phase 1 SHA and unchanged current HEAD: `2592d031550ebd33206c41f855e48f2d2c362f27`.
- The starting local `flutter-manager-development`, origin tracking ref and actual remote branch matched that SHA, ahead/behind `0/0`. The initial working tree contained only `?? docker`.
- The new branch was created directly from that verified SHA. No Phase 2 commit, push, merge, tag, PR or Phase 3 work was performed. The index is empty.
- Flutter 3.47.5 / Dart 3.13.4 and existing packages retained. No dependency, platform project, API, Desktop, Shared Core, SQL, migration, authentication implementation or frozen contract changes.
- Desktop login work remains in stash `df78653663bcbfa5c331a83f2a79bc39d4076f52`. Exact original bytes also remain in `C:/Users/ahmed/.codex/backups/al-mahwar-desktop-login/20261008-3d7c4ed8/`. Both backup SHA-256 hashes and the stash's line-ending-normalized contents were reverified. Neither Desktop file is in this change set.
- Untracked `docker` remains untouched; its SHA-256 remains `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

The frozen contract, relevant Manager DTO/query/permission implementations, released dashboard metric gates and date presets, and Phase 1 architecture were inspected before implementation. The internal preimplementation mapping is ignored evidence at `target/flutter-phase2/endpoint-mapping.txt`.

## Exact reviewable manifest

**14 files: 3 modified, 11 new; 7 production Flutter files, 6 test/support files, 1 report.**

| Status | File | Purpose |
|---|---|---|
| Modified | `mobile/manager_app/lib/core/utils/money.dart` | Reuse exact string grouping for both KWD and quantities |
| New | `mobile/manager_app/lib/features/dashboard/data/dashboard_access.dart` | Released permission intersections; no role-name authorization |
| New | `mobile/manager_app/lib/features/dashboard/data/dashboard_models.dart` | Typed defensive DTOs, calendar dates and supported periods |
| New | `mobile/manager_app/lib/features/dashboard/data/dashboard_repository.dart` | Frozen API mapping through existing authenticated client |
| New | `mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart` | Responsive Arabic dashboard and its states |
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart` | Authorized dashboard landing and existing profile/device navigation |
| New | `mobile/manager_app/lib/features/dashboard/state/dashboard_controller.dart` | Single-flight load, stale guards and auth-scoped state |
| Modified | `mobile/manager_app/test/auth_widgets_test.dart` | Preserve the original keyboard-login/profile-access test after the landing page changes |
| New | `mobile/manager_app/test/dashboard_repository_test.dart` | DTO, decimals, redaction, dates, ranges and endpoint tests |
| New | `mobile/manager_app/test/dashboard_state_test.dart` | State, concurrency, permissions and centralized auth integration |
| New | `mobile/manager_app/test/dashboard_widgets_test.dart` | RTL, visible/hidden values, semantics, states and responsive layouts |
| New | `mobile/manager_app/test/live_dashboard_contract.dart` | Explicit opt-in disposable live API verification |
| New | `mobile/manager_app/test/support/dashboard_fixtures.dart` | Contract fixtures used only by tests |
| New | `docs/FLUTTER_MANAGER_PHASE2_DASHBOARD_REPORT.md` | This report |

No generated verification scripts, images, logs, binaries, local credentials, `target/`, build files or `docker` are reviewable changes. The frozen Phase 1 reports and architecture document are unchanged.

## Endpoints and DTO mapping

All business operations below are existing **GET** operations under `/api/v1/`. Existing Phase 1 authentication, `/me` and session behavior is reused unchanged.

| Endpoint | DTO / use | Permission gate |
|---|---|---|
| `manager/dashboard` | `DashboardOverview`, `OverviewMetric`; authoritative business date and fixed current/monthly metrics | `DASHBOARD`; individual metric intersections below |
| `manager/daily-summary?date=...` | `DailySummary`, optional `SalesSummary`, `ExpenseSummary`, `CashSummary`, `InventorySummary` with inventory snapshot date | `DASHBOARD`; each nested section independently gated |
| `manager/sales/summary?from=...&to=...` | `SalesSummary`; selected week/month sales and optional net profit | `REPORTS_VIEW` + `REPORTS_SALES` |
| `manager/expenses/summary?from=...&to=...` | `ExpenseSummary`; selected week/month expenses | `REPORTS_VIEW` + `REPORTS_EXPENSES` |
| `manager/cashbox/summary?from=...&to=...` | `CashSummary`; opening, IN, OUT, net movement, closing | `CASH` + `REPORTS_VIEW` + `REPORTS_CASHBOX` |
| `manager/sales/top-products?from=...&to=...&limit=5` | `TopProduct`; at most five quantity-ranked products, net quantity/revenue and optional gross profit | Sales report intersection |
| `manager/sales/trend?from=...&to=...&grouping=daily` | `SalesTrendPoint`; exact zero-filled server daily values | Sales report intersection |
| `manager/sales/slow-products?days=30&page=0&size=5` | `DashboardPage<SlowProduct>`; fixed 30-day operational preview | `INVENTORY` + `REPORTS_VIEW` + `REPORTS_INVENTORY` |
| `manager/inventory/low-stock?page=0&size=5` | `DashboardPage<LowStockProduct>`; current stock quantities and minimums | Inventory report intersection |

Overview is loaded first to anchor subsequent dates to the server's business clock. Authorized independent reads then run together. Today reuses daily-summary financial sections: **6 business GETs with full permissions**. Week/month use **9**. Inventory summary is reused from daily-summary instead of fetching it twice. Restricted permissions reduce the request set; the client never probes unauthorized report endpoints.

No invoices, quotations, full sales/product/inventory/party/report modules, administration, audit browser or fake destination screens were added. Dashboard cards present data; navigation exposes only the implemented dashboard and existing profile/devices screen.

## Features and accounting semantics

- Selected-period net sales, optional net profit, expenses and invoice count; fallback authorized overview daily sales/count when sales reports are unavailable.
- Separately labeled current-month dashboard metrics, current cashbook balance, authorized receivables/payables, current active/low/out-of-stock counts and optional inventory valuation.
- Selected-period opening/IN/OUT/net/closing cashbook summary across payment methods, as defined by the API.
- Daily gross sales, returns, return count, gross average invoice, expense entries and cash movement. Daily summary stays daily when the selected period changes.
- Top five products, current low-stock preview, fixed 30-day slow-stock preview and up to seven daily trend rows through the business date. These are actual returned values; no decorative or fabricated chart is used.
- No profit, balance, cost, return allocation, stock valuation or other accounting calculation is reconstructed locally.

Periods follow released semantics exactly:

- Today: business date through business date.
- This week: **Sunday through business date**.
- This month: **first through last calendar day of the month**, including the contract's future zero-filled buckets. It is not silently changed to month-to-date. The displayed trend preview stops at the returned business date.

The SQL/API Kuwait business date is authoritative. UTC `DateTime` values are used only for calendar arithmetic, never device-local accounting time or timezone conversion. Invalid/normalized dates and timestamp-shaped date inputs are rejected. ISO dates are bidirectionally isolated inside Arabic labels. Current inventory is explicitly dated and never presented as a historical balance for the chosen period.

## Architecture and state

`ManagerShell → DashboardScreen → DashboardController → DashboardRepository → existing ApiClient`.

The controller subscribes to the existing auth controller. Its scope includes auth status, session epoch, user ID and the sorted `/me` permissions. Logout, restriction, account/session change or permission change immediately clears data and invalidates earlier requests. Old period/request results cannot overwrite a newer scope or successful period. Disposed screens ignore late completions.

Initial loading shows progress and no invented business values. Refresh retains the previous successful batch and shows progress. Network/server/malformed-response errors use existing safe Arabic error mapping; an explicit stale-data message accompanies retained figures. Initial failure provides retry. Legitimate empty lists have specific empty messages, while legitimate `0.000` remains visible.

Concurrent refresh calls share one pending batch. Period selection invalidates older results. There is no automatic business-request retry loop. Authorization rejection clears figures and notifies the UI **before awaiting `/me` revalidation**. Centralized Phase 1 401 rotation/retry-once and terminal sign-out remain authoritative; dashboard code neither stores nor rotates credentials.

The repository publishes a complete parsed batch after all required reads succeed. It does not claim that multiple API responses form a transactional database snapshot. Count and page statements may reflect concurrent business changes; the client does not demand cross-statement row-count equality.

## Permission and precision safety

- Permissions come from live `/me`; role strings are not used to grant dashboard access.
- Sales reports need `REPORTS_VIEW` + `REPORTS_SALES`; expenses need `REPORTS_VIEW` + `REPORTS_EXPENSES`; cash adds `CASH` + `REPORTS_CASHBOX`; inventory previews need `INVENTORY` + `REPORTS_VIEW` + `REPORTS_INVENTORY`.
- Profit requires `REPORTS_PROFIT`; inventory/slow-stock valuation requires `PRODUCT_COST`. Historical cost and low-stock purchase cost are deliberately never retained by dashboard DTOs.
- Overview today sales/count require `SALES_VIEW` or `FINANCIAL_REPORTS`; monthly sales require `FINANCIAL_REPORTS`; monthly profit additionally requires `REPORTS_PROFIT`; monthly expenses use `EXPENSES`; cash balance uses `CASH`; low-stock metric uses `INVENTORY` or `PRODUCTS`.
- Receivables require `CUSTOMER_PAYMENTS` + `CUSTOMERS_VIEW` + `CUSTOMER_BALANCE_VIEW`; payables require `SUPPLIER_PAYMENTS` + `SUPPLIERS_VIEW` + `SUPPLIER_BALANCE_VIEW`.
- Unauthorized sensitive fields are dropped while parsing even if a response over-returns them, and are gated again in presentation. Missing/redacted permitted sensitive values stay absent, never become zero, and never appear in text or accessibility semantics.
- Malformed required decimal strings/counts/lists/dates/ranges and malformed present sensitive values fail safely. Unknown compatible future overview metrics are ignored; duplicate known visible metrics fail.
- Money and quantities remain exact three-decimal strings. Formatting inserts grouping separators without `double`, rounding or accounting arithmetic. Zero, negative, fractional and values above binary floating-point integer precision are tested. Quantities keep their product unit; no mixed-unit aggregation is invented.
- Complete decimal text stays on one line with scale-down only when needed to fit. It is not truncated, clipped or split between decimal digits; accessibility retains the complete exact value.

## Responsive and visual verification

The existing Arabic locale, RTL routing and Phase 1 light/dark theme are reused. Cards adapt from one to multiple columns according to available width and text scale; descriptions and Arabic labels wrap. The page scrolls vertically, and refresh/retry remain accessible.

Widget checks at **320×700, 390×844 and 800×1024**, including text scale **1.5**, passed without layout exceptions or RenderFlex overflow. Screens contain the large exact monetary fixture. Explicit refresh and pull-to-refresh are tested.

Additionally, three ignored Flutter render checks generated nine light/dark/scroll captures at those widths. Phone/tablet and dark-mode captures were inspected; the first visual pass exposed decimal line splitting and Arabic date reordering, which were corrected and recaptured. Evidence is under `target/flutter-phase2/dashboard-*.png`. These are actual Flutter widget renders using **test-only fixtures and locally loaded Arial for inspection**; they are not screenshots of production business data or native Android certification.

## Fresh verification results

Executed against the final Flutter source:

| Check | Result |
|---|---|
| `dart format --output=none --set-exit-if-changed lib test` | 35 files, 0 changed, exit 0 |
| `flutter analyze --no-pub` | No issues found, exit 0 |
| `flutter test --no-pub` | **113 passed, 0 failures, 0 skips** |
| Existing Phase 1 tests | **55 passed**: auth controller 23, auth widgets 5, contracts 19, transport/storage 8 |
| New Phase 2 default tests | **58 passed**: repository/DTO 29, state 15, widgets 14 |
| Opt-in live dashboard contract | **6 passed**, separate from the 113 default tests |
| Ignored visual render checks | 3 passed; excluded from the reviewable source/default test count |
| `git diff --check` | Pass |

One original Phase 1 widget test now enters the dashboard after keyboard login and opens the existing profile/devices drawer entry before asserting the original device UI. Its password visibility, keyboard login and profile/device checks remain; no original test was removed or skipped.

New tests cover exact/invalid decimals, redacted/over-returned financial fields, omission versus malformed required fields, legitimate empty results, permission intersections, Sunday/full-month/leap calendars, ranges and zero-filled buckets, safe errors, initial/refresh/retry states, stale periods/permissions/logout, simultaneous refresh, immediate 403 hiding before a delayed `/me`, centralized 401 recovery/terminal logout, restricted-session routing/back behavior, RTL, semantics redaction, authorized financial values and practical responsive sizes.

### Live API verification and cleanup

The existing frozen packaged Spring API was launched against uniquely named **disposable business/session databases** created using existing integration fixture procedures. Generated SQL and application credentials were process environment inputs only. The test fixture helper and logs are ignored under `target/flutter-phase2/`; no API source or test file was changed.

Six live tests passed: ADMIN, ACCOUNTANT, CASHIER, STOREKEEPER, must-change-password restriction, and externally revoked session with dashboard data cleared through centralized authentication. All three periods were exercised for each role. ADMIN exercised the union of all nine GET endpoints. The live checks validate actual redacted response fields, typed values, preview stock, independently known fixture sales/returns/profit/cash figures, and `Cache-Control: no-store` on the consumed ADMIN business responses.

Before/after fingerprints of the disposable fixture's product, party, sales, return, expense, cash, stock-movement, ledger and schema tables matched. Auth/session operations use only disposable identity/session data. The real `AlMahwarDB` was not configured as an application or fixture business database and was not mutated; its master metadata remained unchanged. Disposable schemas were verified as business **1.10.0**, API **1.0.0**.

Final cleanup: **0 disposable databases, 0 temporary SQL logins**. The temporary API process was stopped. Generated passwords/SQL credentials and raw access/refresh tokens were absent from verification logs.

### Accepted unchanged backend baseline

API/Desktop/Core/SQL source and tests, the frozen contract and dependency manifests remain equal to HEAD. Expensive backend suites were **not rerun** for this Flutter-only phase. The accepted frozen regression baseline remains:

- Complete API: **637 passed**, 0 failures/errors/skips.
- Desktop: **373 discovered / 168 executed / 205 skipped**, 0 failures/errors.

These are accepted unchanged baseline results, distinct from the freshly executed Flutter and disposable live checks above.

## Security and repository checks

The new/modified Flutter source and tests were scanned for real JWT/refresh credentials, private keys, database connection strings/SQL tools and debug/log interceptors. No findings. Manual inspection found no production passwords, signing secrets, token literals, second network stack, direct database access, financial response dumps or authorization-header logging. Test-only fixture credentials are synthetic; live credentials are supplied externally and never printed.

The production dashboard uses only the existing authenticated REST client and existing secure-storage/auth architecture. No dependency or credential/JWT default changed. Financial DTOs have no raw-JSON diagnostic output; `DashboardData.toString()` is private. Nothing from live logs/scripts/configuration is imported by production Flutter.

Protected local/origin/actual remote refs and existing tags were verified unchanged: main `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b`; API Phase 5 `b86e66eb2fe24f672a73324518813909feca7c62`; API Phase 4 `1b9a46f260d513bfbf3c6391d98a890a3de00878`; API Phase 3 `75bd8d208699261418e97bbf166547820d0742bf`; API Phase 2 `32c673406025c6c78f9adae2c61e604c954c1604`; Core adoption `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99`; API development local/all available remote refs `5cd2819eb2495123c1a41b5db324c267f389d5ee`. Frozen Flutter Phase 1 refs remain at the starting SHA, ahead/behind `0/0`. Desktop release branch and both release tags were also unchanged.

## Practical limits

- No native Android device build or physical-device certification is claimed. Responsive widget/render verification was performed; existing Android licensing/device and Windows native-build prerequisites remain outside this phase.
- Production HTTPS configuration is unchanged. Loopback HTTP was explicitly enabled only in the disposable opt-in development test.
- Current stock/debt and monthly overview figures are independently labeled; the API does not expose a historical inventory snapshot through this dashboard contract.
- Financial data stays in memory and is refreshed on entry/user refresh. Existing session monitoring/revalidation remains in place; this phase adds no background business polling or offline financial cache.
- Preview lists intentionally stop at five products and trend at seven elapsed/calendar-to-business-date rows. Full business browsing belongs to later approved Flutter phases.
- Multiple GETs can observe concurrent business activity. No database-wide snapshot or cross-response accounting reconciliation is claimed.

## Final Git review

The reviewable set is exactly the 14 files above. The index remains empty. Standard `git diff --stat` / `git diff --name-only` list the 3 modified tracked files; the 11 new files are separately accounted for by `git ls-files --others --exclude-standard`, excluding unrelated `docker`. No staging was used to make new files appear in a diff.

Exact `git status --short`:

```text
 M mobile/manager_app/lib/core/utils/money.dart
 M mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart
 M mobile/manager_app/test/auth_widgets_test.dart
?? docker
?? docs/FLUTTER_MANAGER_PHASE2_DASHBOARD_REPORT.md
?? mobile/manager_app/lib/features/dashboard/data/
?? mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart
?? mobile/manager_app/lib/features/dashboard/state/
?? mobile/manager_app/test/dashboard_repository_test.dart
?? mobile/manager_app/test/dashboard_state_test.dart
?? mobile/manager_app/test/dashboard_widgets_test.dart
?? mobile/manager_app/test/live_dashboard_contract.dart
?? mobile/manager_app/test/support/dashboard_fixtures.dart
```

**FLUTTER MANAGER PHASE 2 DASHBOARD READY FOR REVIEW**

# Flutter Manager Phase 4 — Sales, Invoices & Returns

Status: **IMPLEMENTED — READY FOR HUMAN REVIEW; UNCOMMITTED / UNPUSHED**.
Date: 2026-10-09.
Branch: `flutter-manager-phase4-sales`.
Base and unchanged HEAD: `ae68338e768a33c48f72b0d65caa33c361600d3a`.

## Scope and baseline

The accepted Phase 3 branch, origin tracking ref and actual remote were verified at the base SHA, with ahead/behind 0/0. Initial status contained only `?? docker`. This branch was created directly from that accepted commit. No file has been staged, committed or pushed for Phase 4.

This is a Flutter-only, authenticated, read-only implementation. Desktop, API, Core, SQL, schemas/migrations, the frozen contract, dependencies, authentication/session implementation, platform configuration, inherited inventory implementation/tests and historical Phase 1/2/3 reports remain byte-for-byte unchanged. The two existing Flutter presentation files changed only to add permission-aware navigation and Dashboard actions.

The supplied request ends abruptly in section 16 at `Sales screen →`. The available requirements through that point were followed; no additional phase or business mutation was inferred. An optional request for the remaining text was issued. This report records the implemented scope for review.

## Exact reviewable manifest — 16 files

**2 modified + 14 new**. No generated outputs or verification helpers belong to this manifest.

| Status | Repository-relative file |
|---|---|
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart` |
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart` |
| New | `mobile/manager_app/lib/features/sales/data/sales_access.dart` |
| New | `mobile/manager_app/lib/features/sales/data/sales_models.dart` |
| New | `mobile/manager_app/lib/features/sales/data/sales_repository.dart` |
| New | `mobile/manager_app/lib/features/sales/state/sales_controller.dart` |
| New | `mobile/manager_app/lib/features/sales/presentation/sales_widgets.dart` |
| New | `mobile/manager_app/lib/features/sales/presentation/sales_screen.dart` |
| New | `mobile/manager_app/lib/features/sales/presentation/invoice_list.dart` |
| New | `mobile/manager_app/lib/features/sales/presentation/invoice_detail_screen.dart` |
| New | `mobile/manager_app/test/support/sales_fixtures.dart` |
| New | `mobile/manager_app/test/sales_repository_test.dart` |
| New | `mobile/manager_app/test/sales_state_test.dart` |
| New | `mobile/manager_app/test/sales_widgets_test.dart` |
| New | `mobile/manager_app/test/live_sales_contract.dart` |
| New | `docs/FLUTTER_MANAGER_PHASE4_SALES_REPORT.md` |

`docker` remains separate, untracked and unchanged. All temporary scripts, SQL/HTTP harnesses, logs, rendered images and build outputs are ignored under `target/flutter-phase4/` or `mobile/manager_app/build/`.

## Verified endpoint mapping

Discovery preceded implementation. Sources: the frozen `docs/API_MANAGER_CONTRACT_V1.md`, accepted Flutter Phase 2/3 reports, current typed models/auth/client, Manager sales controller/DTOs, `ManagerDateRange`, document controller/service/filter/repository/DTOs, authorization tests and SQL integration assertions. The initial mapping was recorded in ignored `target/flutter-phase4/endpoint-mapping.txt` before coding.

All paths below are relative to `/api/v1/` and use **GET only**.

| Endpoint | Allowlisted query and response | Permission / sensitive fields | Presentation |
|---|---|---|---|
| `manager/sales/summary` | `period=today/this_week/this_month`, or custom inclusive `from/to`, max 366 days. `range`, gross/returns/net, invoice/return counts, average and optional profit. | `REPORTS_VIEW` AND `REPORTS_SALES`; optional net profit requires `REPORTS_PROFIT`. Summary historical cost is deliberately not retained. | Sales overview; server range anchors the next two requests. |
| `manager/sales/trend` | Anchored `from/to`, `grouping=daily`; chronological zero-filled daily buckets. | Same report gate; no cost/profit fields. | Expandable table of actual daily net sales and invoice counts. |
| `manager/sales/top-products` | Same anchored range, `limit=5`; product code/name/unit, net quantity/revenue, optional gross profit. | Same report gate; optional `REPORTS_PROFIT`. | Top five products with separate units; no quantity aggregation. |
| `manager/invoices` | Period/custom range; literal `q` max100; positive `customerId`; `status=DRAFT/POSTED/CANCELLED`; `paymentMethod=CASH/KNET/CREDIT_CARD/BANK_TRANSFER/CHEQUE/CREDIT/MIXED`; page0–10000, size1–100. Fixed date/number/total sorts. Typed invoice page with per-row `returnCutoffDate`. | `SALES_VIEW`; `historicalCost` requires `SALES_COST_VIEW`; original `grossProfit` requires `SALES_PROFIT_VIEW` and POSTED status. | Compact invoice browser, server search/filter/sort/pagination. |
| `manager/invoices/{id}` | Positive Java-int ID; independent `page/size` for line items and `returnsPage/returnsSize` for return headers. Header, typed line page, typed return page. Fixed ID order, no line search/sort. | `SALES_VIEW`; same header cost/profit gates; line `historicalUnitCost` requires `SALES_COST_VIEW`. | Invoice identity/customer/creator, original payment values, all-time returns/refunds, lines, authorized historical fields. |

Preset dates come from the server; there is no phone-clock business-date calculation or prerequisite Dashboard access. The released week starts Sunday and ends on the server business day. The month covers the full calendar month. Custom ranges validate real calendar dates, order, 1900–9998 bounds and at most 366 inclusive days. The summary's authoritative range anchors trend/top even across a business-date boundary.

Sales reporting and invoice permissions are independent. Cost/profit gates for invoices are distinct from `PRODUCT_COST` and report-profit permissions. No role-name authorization is used.

## Accounting and return semantics

- Gross sales, returns, net sales, original invoice totals, stored payment snapshots, return value, cash refund and profit are labeled separately. No financial rule is reconstructed on the phone.
- Average invoice is the backend gross average before returns. The report's net profit is already after returns and expenses. Returns are never subtracted a second time.
- Period reports attribute returns to the return date, including returns against invoices from an earlier period. A live return-only day verifies negative net sales with zero invoices.
- Invoice-list return values/refunds are accumulated only through each row's inclusive `returnCutoffDate`. Detail headers and line returned quantities include all recorded dates, including later returns.
- Original `paid`, `remaining` and `paymentStatus` remain the stored invoice snapshot. Remaining is explicitly not presented as the customer's current post-return debt.
- Header tax, whole-line discount and stored line total are displayed exactly. No per-line tax allocation or discount-rate calculation is invented.
- Historical invoice cost and POSTED-only original gross profit remain original historical values, not return-adjusted current profit. Non-POSTED over-returned profit is discarded.
- Product labels/units in detail are current joined labels; transaction quantities/prices/costs are historical. The UI explains that distinction.
- All authoritative money and quantities stay exact decimal strings with three decimal places, using released formatters; no binary floating-point accounting.

## State, security and navigation

The new feature follows screen → feature controller → typed repository → released authenticated `ApiClient`. It reuses released calendar/page/model helpers through acyclic Dart imports. Authentication, secure storage and centralized singleflight refresh/signout are unchanged.

Controllers include auth status, user, session epoch and sorted permission codes in the request scope. Logout, password restriction, permission changes and session invalidation clear protected models immediately and invalidate pending responses. Cost/profit removal clears even previously loaded authorized values before a reload completes. Unauthorized over-returned sensitive fields are discarded during DTO parsing and gated again by the UI. Missing authorized optional values remain absent; malformed present values fail safely. Document diagnostic strings are private; production code does not log business responses or financial values.

Text search is trimmed, capped at100 and debounced350ms. Search/clear/filter/sort/range/customer changes invalidate stale requests and restart at page0. No client-only global filtering is claimed. Query parameters are URI-encoded; sort/status/payment values come only from enums. Next-page operations are singleflight, advance on success, deduplicate document IDs across overlapping pages, stop on empty/end pages and retry the same failed page. Safe previously loaded data stays visible with its previous context during transient refresh errors.

Invoice detail has independent line/return cursors and pending operations. Paging one collection does not reset the other or overwrite the header snapshot. Refresh resets both on success and invalidates late appends. A 401/403/404 clears the entire detail and both collections; a parallel stale result cannot republish it while `/me` is still pending. A transient nested failure preserves the other collection and retries only the failed one.

The Manager drawer exposes Sales & Invoices only with report or invoice access. The feature exposes only authorized overview/invoice sections. Dashboard actions open the real destinations. The customer reference returns to the server-filtered invoice browser. Android route back, existing inventory, profile and device flows remain intact. A revoked detail cannot retain prior financial content.

## Contract limitations retained

- No standalone period-wide returns browser, return-item detail or payment-history endpoint exists for this experience; none is invented. Invoice return headers are explicitly scoped to that invoice.
- `paymentStatus` is displayed but is not an accepted invoice-list query filter. The supported filter is payment method.
- Customer references support navigation/filtering, not account-balance retrieval or debt calculation.
- Every detail request returns both nested collections. Paging one necessarily fetches an unused first page of the other, which is discarded; no nonexistent item/return endpoint is added.
- The frozen API does not promise a transaction snapshot across requests. The UI retains the last explicit header refresh, validates each returned page and handles identity overlap; concurrent backend changes can change counts/results. It does not claim snapshot consistency.
- No create/edit/post/cancel/return/refund/payment/POS action, business write, offline financial cache, export feature, dependency addition or next-phase work is included.

## Fresh verification

Toolchain: Flutter **3.47.5**, Dart **3.13.4**.

| Check | Result |
|---|---|
| `dart format --output=none --set-exit-if-changed lib test` | Clean; 61 files, zero changes |
| `flutter analyze --no-pub` | No issues found |
| `flutter test --no-pub` | **316 passed, 0 failures, 0 skips** |
| Inherited tests | **206 unchanged/passed**: Phase1 55 + Phase2 58 + Phase3 93 |
| New Phase4 tests | **110 passed**: repository/DTO49 + state23 + widget38 |
| Explicit disposable live test | **8 passed**, separate from316 |
| Actual Flutter render harness | **9 passed**, 36 PNGs; separate from316 |
| `git diff --check` | Clean |
| Index | Empty |

Coverage includes all five GET routes, independent permissions, absent/malformed/over-returned protected fields, non-POSTED profit, exact large/fractional/zero/signed amounts, local timestamp/calendar validation, server period anchoring, zero-filled trend validation, literal search, fixed filters/sorts, independent nested pagination, retry/singleflight/stale response handling, 403/404 clearing, live `/me` changes, restriction/logout, inherited 401 refresh and terminal signout, real navigation and safe UI errors.

UI/render verification covers Arabic RTL, 320×700 / 390×844 / 800×1024, text scale1.5, populated/loading/empty/error/retry states and long identifiers/large exact decimals. Light and dark actual Flutter renders were generated and inspected; long invoice identifiers wrap at the selected text size rather than shrinking to an unreadable single line. No RenderFlex overflow, clipped numeric values or horizontal page scrolling was observed. The render harness and images remain ignored verification artifacts.

### Live fixture and cleanup

`test/live_sales_contract.dart` requires explicit disposable-mode URL and runtime password environment variables; it is not discovered by the normal `_test.dart` suite. The ignored Java/PowerShell harness reused established temporary DB helpers and the existing packaged API. It generated runtime credentials, verified the temporary SQL login had **no access to real AlMahwarDB**, created only disposable databases and launched a temporary API process against them. Synthetic seed data exists only in tests/disposable databases, never production UI/repositories.

Four live released roles independently exercised frontend/backend permissions and field redaction; successful business responses were GET-only with Cache-Control no-store. Additional tests verified stored tax/discount/price/cost/payment amounts, independently paged lines/returns, later returns versus list cutoff, a return-only day, restricted credentials and cross-session revocation. The before/after disposable business-table fingerprints matched. Fixture schemas remained **business1.10.0 / API1.0.0**.

Cleanup after every live run: **0 disposable databases, 0 temporary SQL logins**, temporary API stopped. Real AlMahwarDB was never the fixture/API target and its master metadata was unchanged. Final read-only inspection confirmed real **AlMahwarDB schema1.10.0**. The permanent `AlMahwarApiDB` schema table is absent in this environment; it was not bootstrapped or changed. The API schema1.0.0 assertion refers to the disposable API database and the unchanged released schema sources, not an invented permanent-DB result.

Runtime SQL credentials/generated passwords and raw access/refresh tokens were absent from verification logs. Source scans found no hardcoded real credentials/JWT secrets, direct SQL access, business mutation transport, logging of sensitive data, or floating-point financial parsing. Early test-harness assertions for week-end date and independent profit expected value were corrected against the released rules/seed arithmetic, then all applicable tests rerun. Semantics-handle disposal and UI finder expectations were also corrected without weakening inherited tests.

### Accepted backend baselines — not rerun

No complete Maven/API/Desktop suite was rerun for this Flutter-only change. The inherited accepted results remain:

- API: **637 passed / 0 failures/errors/skips**.
- Desktop: **373 discovered / 168 executed / 205 skipped / 0 failures/errors**.

Fresh verification above is Flutter plus the eight independent disposable live HTTP/API tests. Protected-source hashes confirm backend/Core/Desktop/test/SQL/dependency files did not change.

## Protected state and Desktop preservation

**555 protected tracked files** matched their pre-implementation SHA256 hashes. All existing local branches, origin tracking refs, actual remote heads and tag objects matched the saved baseline. No protected ref or tag moved; no remote Phase4 branch was created.

| Protected branch / ref | SHA |
|---|---|
| main / origin/main / actual remote main | `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b` |
| API Phase5 | `b86e66eb2fe24f672a73324518813909feca7c62` |
| API Phase4 | `1b9a46f260d513bfbf3c6391d98a890a3de00878` |
| API Phase3 | `75bd8d208699261418e97bbf166547820d0742bf` |
| API Phase2 | `32c673406025c6c78f9adae2c61e604c954c1604` |
| API core adoption | `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99` |
| API development (local) | `5cd2819eb2495123c1a41b5db324c267f389d5ee` |
| Desktop release branch | `5f9e9e0a740279496e3603caf556fbb31f8a5fe3` |
| Flutter Phase1 / separate Desktop redesign branch | `2592d031550ebd33206c41f855e48f2d2c362f27` |
| Flutter Phase2 | `b17b6df4fd5196af8ee156356f749063b4e9b684` |
| Flutter Phase3 and current Phase4 HEAD | `ae68338e768a33c48f72b0d65caa33c361600d3a` |
| v1.0.0 tag object | `7202640ad2f44ef879920cff2d2b08ab13fb6446` |
| v1.0.1 tag object | `8b97ebb60e333522f784e63935cb2ad2b088c627` |

The Desktop login redesign stash **`df78653663bcbfa5c331a83f2a79bc39d4076f52`** remains present and unaltered; it was not restored, dropped or mixed with Flutter. Exact-byte backups under `C:\Users\ahmed\.codex\backups\al-mahwar-desktop-login\20261008-3d7c4ed8` retain SHA256:

- login.fxml: `F73F037470594D0BF664D202041D817873F392FC45715C08ED5689E9536297C9`.
- styles.css: `74768798C3228FF15B62B6CD25E96E2EF837D13165C6A151F869879A142D4E06`.

Final short status (Git's normal untracked-directory grouping):

```text
 M mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart
 M mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart
?? docker
?? docs/FLUTTER_MANAGER_PHASE4_SALES_REPORT.md
?? mobile/manager_app/lib/features/sales/
?? mobile/manager_app/test/live_sales_contract.dart
?? mobile/manager_app/test/sales_repository_test.dart
?? mobile/manager_app/test/sales_state_test.dart
?? mobile/manager_app/test/sales_widgets_test.dart
?? mobile/manager_app/test/support/sales_fixtures.dart
```

No commit, push, merge, tag, PR, Desktop redesign restoration, Phase5 or SQL1205 change. Stop for human review.

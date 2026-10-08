# Flutter Manager Phase 3 — Products & Inventory

Date: **2026-10-08**. Status: **READY FOR HUMAN REVIEW; UNCOMMITTED**.

Branch: `flutter-manager-phase3-inventory`.
Starting accepted Phase 2 SHA and current HEAD: **`b17b6df4fd5196af8ee156356f749063b4e9b684`**.

The accepted Phase 2 branch, origin tracking ref and actual remote were verified at that SHA with ahead/behind **0/0** before creating this local branch. Initial working tree contained only `?? docker`. No unexpected work was stashed or overwritten.

## Exact reviewable manifest

Exactly **16 files: 2 modified, 14 new**. No staging was used.

| Change | File |
|---|---|
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart` |
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart` |
| New | `mobile/manager_app/lib/features/inventory/data/inventory_access.dart` |
| New | `mobile/manager_app/lib/features/inventory/data/inventory_models.dart` |
| New | `mobile/manager_app/lib/features/inventory/data/inventory_repository.dart` |
| New | `mobile/manager_app/lib/features/inventory/state/inventory_controller.dart` |
| New | `mobile/manager_app/lib/features/inventory/presentation/inventory_widgets.dart` |
| New | `mobile/manager_app/lib/features/inventory/presentation/inventory_screen.dart` |
| New | `mobile/manager_app/lib/features/inventory/presentation/product_detail_screen.dart` |
| New | `mobile/manager_app/lib/features/inventory/presentation/movement_screen.dart` |
| New | `mobile/manager_app/test/support/inventory_fixtures.dart` |
| New | `mobile/manager_app/test/inventory_repository_test.dart` |
| New | `mobile/manager_app/test/inventory_state_test.dart` |
| New | `mobile/manager_app/test/inventory_widgets_test.dart` |
| New | `mobile/manager_app/test/live_inventory_contract.dart` |
| New | `docs/FLUTTER_MANAGER_PHASE3_INVENTORY_REPORT.md` |

## Discovery and frozen endpoint mapping

Discovery inspected the frozen `API_MANAGER_CONTRACT_V1.md`, Phase 1 architecture/report, Phase 2 report, existing Flutter auth/network/decimal implementation, API product/Manager implementations and tests, and the released Shared Core product permission lookup before coding. The internal preimplementation mapping is under ignored `target/flutter-phase3/endpoint-mapping.txt`.

All inventory business requests use the existing authenticated `ApiClient`, with **GET only**:

| Frozen endpoint | Parameters used | Typed response / destination | Permission | Sensitive field |
|---|---|---|---|---|
| `/api/v1/products` | `q`, zero-based `page`, `size`, fixed `sort`, `includeInactive` | Page of products: identity, exact quantity/minimum, active, sale/wholesale price; product list | `PRODUCTS_VIEW` OR `PRODUCTS`; inactive additionally `PRODUCTS` | Optional `purchasePrice` |
| `/api/v1/manager/inventory/low-stock` | `q`, `page`, `size`, fixed `sort` | Page of stock products: id/code/name/unit/quantity/minimum; low-stock list | `INVENTORY` AND `REPORTS_VIEW` AND `REPORTS_INVENTORY` | Optional `purchaseCost` |
| `/api/v1/manager/products/{id}` | Positive product ID | Product detail: actual identity, selling price, quantity/minimum, active and server `lowStock` | `PRODUCTS_VIEW` OR `PRODUCTS`; inactive additionally `PRODUCTS` | Optional `purchaseCost` |
| `/api/v1/manager/products/{id}/movements` | Positive ID; server `period` preset or custom `from`/`to`; `page`, `size`, fixed `date,desc` | Page of local business datetime/type/typeName/signed quantity/before/after; movement history | Inventory permission intersection above **and** product view permission | Optional `unitCost` |

All sensitive fields additionally require live **`PRODUCT_COST`**. Role labels do not authorize. The released movement service calls Shared Core's protected product lookup (`PRODUCTS_VIEW` OR `PRODUCTS`) as well as the Manager inventory gate; the client therefore requires both. The low-stock DTO contains no barcode, selling price or active flag; membership is authoritative and its query still supports barcode search. Movement responses contain **no ID or reference field**; neither is invented.

## Experience and navigation

- Manager shell exposes Products/Inventory only with product access or the full inventory intersection. Dashboard inventory and low-stock actions open the real destination. Profile/devices, dashboard, logout and must-change-password flows remain intact.
- Compact Arabic RTL rows prioritize name/code, exact quantity/unit, textual stock status and selling price when provided. Unit purchase cost appears only with permission and a present field. Product rows open the real detail endpoint only with product permission. An inventory-only user receives low stock with no protected detail link.
- Search accepts keyboard, pasted barcode or a hardware scanner's text. The full Arabic label sits above the field for narrow enlarged-text layouts. Search trims whitespace, is bounded to 100 UTF-16 units and uses a 350 ms debounce; keyboard submit is immediate. Queries are URI encoded and server backed, including literal wildcard characters. Clearing search returns to the normal page-zero query.
- Low stock uses its authoritative endpoint. Standard product stock labels use the released active-and-quantity-at-or-below-minimum rule, compared as exact scaled integers; detail uses server `lowStock`. Zero/negative quantity is labeled out of stock, inactive is labeled inactive. Status is conveyed by text rather than color alone.
- Detail groups actual identity fields, quantity/minimum/status and financial fields. Optional metadata is never fabricated. Movement access is permission gated. History shows the server's Arabic type name, unchanged local business timestamp, signed quantity and before/after balances with the supplied unit.
- Initial load, refresh, searching, next-page load, empty and safe-error states are distinct. Prior safe content can remain during refresh/search with the last successful query context explicitly displayed; failed search does not claim old results match the new query. Pagination failure offers retry for the same page. Narrow error layouts put retry below the shared safe error message.
- Movement range defaults to the **server's this-month preset**, avoiding device-clock assumptions. Today/week/month presets are server resolved. Custom business dates use validated ISO dates and at most 366 inclusive days. History never loads without a bounded range.

## Precision, security and request state

API decimal strings remain exact three-decimal strings through parsing and the existing Phase 2 `formatKwd` / `formatQuantity` functions. No authoritative monetary arithmetic uses `double`. Stock comparisons use `BigInt` scaled integers. Large values including `9007199254740993.125`, signed movements, fractional quantities and zero are tested. Units are not aggregated; cost or stock valuation is not calculated.

DTO parsing discards unauthorized over-returned cost before it enters typed presentation models. Missing cost stays absent, never `0.000`; malformed present authorized cost fails safely. Product/page/movement diagnostic strings are private. Cost removal, logout, restriction and permission/session-scope changes immediately clear protected controller data and invalidate pending generations. Widget text and accessibility semantics are tested for unauthorized cost absence, including delayed reload after permission loss.

Controllers subscribe to the existing auth controller and scope requests to status, session epoch, identity and sorted live permissions. Generation checks reject stale search/filter/product/detail/page responses. Refresh supersedes pending pagination; duplicate concurrent refreshes or next-page requests share one pending operation. Product-ID changes clear old detail/history. A 403 clears data and notifies before slow `/me` completes. Central Phase 1 refresh/retry/signout handles 401; no inventory refresh-token flow or secure store was added.

Pages default to **20**, repository size bounds are **1–100**, and pages are **0–10000**. Successful next pages advance exactly once; failure retains the previous page. Search/filter/sort/range changes restart at page zero. Product identity overlap across pages is suppressed. Empty next pages stop progression. Equal-valued movement rows are retained because multiple legitimate events can have identical values and no movement ID is exposed. Sort controls map only to fixed name ascending/descending, code ascending and quantity ascending/descending values accepted by both list endpoints; movements remain date descending. There is no arbitrary sort-string input or client-only authoritative filtering.

## Fresh verification

Installed toolchain: **Flutter 3.47.5**, **Dart 3.13.4**.

From `mobile/manager_app`, the final source passed:

```text
dart format --output=none --set-exit-if-changed lib test
  Formatted 48 files (0 changed); exit 0
flutter analyze --no-pub
  No issues found; exit 0
flutter test --no-pub --reporter json
  206 passed; 0 failures; 0 skips; exit 0
```

| Suite group | Count |
|---|---:|
| Inherited Phase 1 | 55 |
| Inherited Phase 2 | 58 |
| New Phase 3 repository/DTO | 37 |
| New Phase 3 state/auth integration | 21 |
| New Phase 3 widget/navigation/security/responsive | 35 |
| **Inherited total** | **113** |
| **New Phase 3 total** | **93** |
| **Default Flutter total** | **206** |

Inherited tests remain unchanged. The additional opt-in live suite and ignored render harness are counted separately below.

Responsive widget checks exercise list/detail/movements at **320×700, 390×844 and 800×1024**, all at **150% text scale**, including huge exact decimals. Narrow error/retry layouts also pass. No RenderFlex overflow was observed. An ignored Flutter render harness passed **9 render tests**, producing light/dark top/bottom PNG evidence with real Arabic-capable fonts under `target/flutter-phase3/`; representative renders were visually inspected. No screenshot fixture or render helper is production code.

### Disposable live API verification

Explicit opt-in: `flutter test test/live_inventory_contract.dart --no-pub`. It refuses to run without externally supplied API URL/password and `MANAGER_TEST_DISPOSABLE=true`.

**6 tests passed:** ADMIN, ACCOUNTANT, CASHIER, STOREKEEPER, must-change-password restriction, and externally revoked session clearing cached inventory via central auth. Checks exercise real frozen product list/search/barcode/detail/low-stock/movement endpoints, exact independent seeded values, literal wildcard search, product and movement pagination, all exposed product sorts, inactive filtering for authorized users, raw response cost omission, backend authorization rejection, and successful business responses' `Cache-Control: no-store`.

The ignored Java fixture helper starts the unchanged packaged API against uniquely named disposable business/session databases. It seeds only those databases, including a known barcode. Its temporary SQL login was explicitly verified unable to access **real `AlMahwarDB`** before fixture creation. Generated passwords and JWT signing material are environment supplied and never printed.

Before/after fingerprints of the disposable Products, Customers, Suppliers, Sales, Sale_Items, Sale_Returns, Sale_Return_Items, Expenses, Cash_Transactions, Stock_Movements, Account_Ledger and Schema_Info tables matched. The fixture schemas were **business 1.10.0 / API 1.0.0**. Real business data was never targeted by the fixture/API; real database master metadata also remained unchanged.

Final cleanup: **0 disposable databases, 0 temporary SQL logins**. The temporary API process stopped. Runtime SQL credentials, generated passwords and raw access/refresh tokens were absent from verification logs. An initial live assertion counted a deliberate 403 alongside successful responses; the observer was corrected to count successful responses consistently and the entire six-test live suite was rerun successfully, with cleanup verified after both runs.

### Security and unchanged backend baseline

Targeted changed-file scans found no embedded JWT/refresh credentials, private keys, direct SQL/JDBC/SQL tools, debug/log response dumps or hardcoded production secrets. Manual scope review found no second auth/network/storage mechanism, auth-header logging, inventory mutations, fake production data, mobile selling, camera/plugin addition or dependency change. Live test responses exist only in test memory and are not logged.

**508 protected tracked files outside Flutter lib/test** matched their preimplementation raw SHA256 hashes. This includes Desktop/API/Core/SQL, frozen contract, dependency/platform files and historical reports. Auth/network/storage implementation and all inherited tests have no Git diff. No backend/schema/dependency changes occurred.

Expensive backend suites were **not rerun** for this Flutter-only phase. Accepted unchanged baseline:

- API: **637 passed / 0 failures/errors/skips**.
- Desktop: **373 discovered / 168 executed / 205 skipped / 0 failures/errors**.

## Practical limits

- The contract exposes unit purchase cost and historical movement unit cost, not a product-list stock valuation field. No valuation is synthesized. No camera scanning, images, mutations or direct database access were introduced.
- Offset pages/count queries can observe concurrent business changes. The client guards its own duplicate/stale requests; the frozen API provides no snapshot token or guarantee against rows moving between pages. Identical movement events cannot safely be deduplicated. Server-period queries can also cross a business-date boundary; custom dates provide an explicit stable range.
- Movement presets do not return a resolved range/server date; labels identify the selected server preset rather than inventing dates from the device clock. Local business timestamps are displayed without device timezone conversion.
- Business data remains in memory. No offline inventory cache, unbounded prefetch or new business polling was added. Existing `/me` monitoring remains responsible for discovering server-side permission changes.
- Responsive/render/live verification is complete; no physical Android device certification or native build is claimed. Existing native-build prerequisites remain outside this phase.

## Preservation and protected refs

Desktop login redesign remains separately preserved in stash **`df78653663bcbfa5c331a83f2a79bc39d4076f52`**, and exact-byte backups under `C:\Users\ahmed\.codex\backups\al-mahwar-desktop-login\20261008-3d7c4ed8`. Both backup hashes were reverified; the stash and `codex/desktop-login-redesign` ref remain unchanged. The redesign was not restored, deleted or included here.

All existing local/origin refs and actual remote heads/tags matched the initial snapshot; only the requested new local Phase 3 branch was created. Verified protected values:

| Ref | SHA |
|---|---|
| main / origin / actual remote | `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b` |
| API Phase 5 | `b86e66eb2fe24f672a73324518813909feca7c62` |
| API Phase 4 | `1b9a46f260d513bfbf3c6391d98a890a3de00878` |
| API Phase 3 | `75bd8d208699261418e97bbf166547820d0742bf` |
| API Phase 2 | `32c673406025c6c78f9adae2c61e604c954c1604` |
| API Core adoption | `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99` |
| API development (local; no remote ref present) | `5cd2819eb2495123c1a41b5db324c267f389d5ee` |
| Flutter Phase 1 / Desktop redesign branch | `2592d031550ebd33206c41f855e48f2d2c362f27` |
| Flutter Phase 2 | `b17b6df4fd5196af8ee156356f749063b4e9b684` |
| Desktop release branch | `5f9e9e0a740279496e3603caf556fbb31f8a5fe3` |
| v1.0.0 tag object | `7202640ad2f44ef879920cff2d2b08ab13fb6446` |
| v1.0.1 tag object | `8b97ebb60e333522f784e63935cb2ad2b088c627` |

## Final Git review

`git diff --check` passes. Standard tracked `git diff --stat` reports **2 files / 92 insertions / 13 deletions**; new files are separately inventoried using `git ls-files --others --exclude-standard`, excluding unrelated `docker`. The full 16-file manifest above includes the report. The index is empty. Verification scripts, screenshots and logs remain ignored under target/build. `docker` remains untracked with its original empty-file hash.

Exact `git status --short`:

```text
 M mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart
 M mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart
?? docker
?? docs/FLUTTER_MANAGER_PHASE3_INVENTORY_REPORT.md
?? mobile/manager_app/lib/features/inventory/
?? mobile/manager_app/test/inventory_repository_test.dart
?? mobile/manager_app/test/inventory_state_test.dart
?? mobile/manager_app/test/inventory_widgets_test.dart
?? mobile/manager_app/test/live_inventory_contract.dart
?? mobile/manager_app/test/support/inventory_fixtures.dart
```

No stage, commit, push, merge, tag, PR or Phase 4. Stop for human review.

**FLUTTER MANAGER PHASE 3 INVENTORY READY FOR REVIEW**

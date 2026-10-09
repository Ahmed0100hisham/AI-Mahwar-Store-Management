# Flutter Manager Phase 5 — Customers, Suppliers, Balances & Statements

Date: 2026-10-09. Status: **IMPLEMENTED, UNCOMMITTED / UNPUSHED, READY FOR REVIEW**.
Branch: `flutter-manager-phase5-parties`.
Starting and unchanged HEAD: `464aa6c0da386f710e4db888fe22c746cf026597`.

## Verified baseline and scope

The complete 28-section Phase 5 request was read. The accepted Phase 4 local branch, origin tracking ref and actual remote matched the above SHA, ahead/behind 0/0. Initial Git status contained only `?? docker`, with an empty index. This branch was created directly from that verified SHA.

This is an authenticated Flutter-only read implementation. No Desktop/API/Core/SQL/schema/migration/seed/authentication/session/frozen-contract/dependency/platform file or historical report changed. No party/payment/business mutation, POS, next-phase work, commit, push, merge/rebase, tag or PR was performed. Synthetic records exist only in tests and disposable DB setup. Production screens use actual authenticated responses.

Before coding, the frozen contract, Phase 1 architecture, Phase 2/3/4 reports, Manager controller/access/query/DTO/repository and SQL integration tests were inspected. The mapping was recorded first in ignored `target/flutter-phase5/endpoint-mapping.md`. Verification scripts, logs, Java fixture helpers, images and generated files remain ignored.

## Exact reviewable manifest — 16 files

**2 modified + 14 new**: eight new production Flutter files, five new test/support files, one new report. Nothing is staged.

| Status | Repository-relative path |
|---|---|
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart` |
| Modified | `mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart` |
| New | `mobile/manager_app/lib/features/parties/data/party_access.dart` |
| New | `mobile/manager_app/lib/features/parties/data/party_models.dart` |
| New | `mobile/manager_app/lib/features/parties/data/party_repository.dart` |
| New | `mobile/manager_app/lib/features/parties/state/party_controller.dart` |
| New | `mobile/manager_app/lib/features/parties/presentation/party_widgets.dart` |
| New | `mobile/manager_app/lib/features/parties/presentation/party_screen.dart` |
| New | `mobile/manager_app/lib/features/parties/presentation/party_detail_screen.dart` |
| New | `mobile/manager_app/lib/features/parties/presentation/party_account_screen.dart` |
| New | `mobile/manager_app/test/support/party_fixtures.dart` |
| New | `mobile/manager_app/test/party_repository_test.dart` |
| New | `mobile/manager_app/test/party_state_test.dart` |
| New | `mobile/manager_app/test/party_widgets_test.dart` |
| New | `mobile/manager_app/test/live_parties_contract.dart` |
| New | `docs/FLUTTER_MANAGER_PHASE5_PARTIES_REPORT.md` |

## Frozen endpoint / DTO / permission mapping

Every business request is **GET**, relative to `/api/v1/`, through the existing authenticated `ApiClient`. No new network client, interceptor, token storage, auth endpoint or backend route was introduced.

| Endpoint | Allowlisted parameters / DTO | Permission / sensitive fields | Destination |
|---|---|---|---|
| `manager/customers` | page, size, literal q; name/code/balance asc/desc. PartyList(page, optional totalOutstanding). Party(id,code,name,phone?,area?,active,balance optional). | CUSTOMERS_VIEW; balance and its sort require CUSTOMER_BALANCE_VIEW. Normal-list aggregate is not retained. | Customers browser |
| `manager/customers/receivables` | Same bounded list query; default balance,desc; authoritative aggregate over every matching positive ledger debtor, including inactive. | CUSTOMERS_VIEW AND CUSTOMER_BALANCE_VIEW; balance/totalOutstanding protected. | Customer debts |
| `manager/customers/{id}` | Positive Java-int id; Party identity/contact and optional cached current balance. | CUSTOMERS_VIEW; balance adds CUSTOMER_BALANCE_VIEW. | Customer details |
| `manager/customers/{id}/account` | id; today/this_week/this_month or custom from/to; max366 inclusive days; page/size; fixed date,asc; no q. Account(partyId,range,openingBalance,totalDebit,totalCredit,closingBalance,entries page). | CUSTOMERS_VIEW AND CUSTOMER_BALANCE_VIEW; whole statement protected. | Customer statement |
| `manager/suppliers` | Same list DTO/query, supplier identity and independently protected balance. | SUPPLIERS_VIEW; balance/sort adds SUPPLIER_BALANCE_VIEW. | Suppliers browser |
| `manager/suppliers/payables` | Same debt-list query and wrapper; positive supplier ledger liabilities including inactive; all-matches totalOutstanding. | SUPPLIERS_VIEW AND SUPPLIER_BALANCE_VIEW. | Supplier dues |
| `manager/suppliers/{id}` | Positive id; Party. | SUPPLIERS_VIEW; balance adds SUPPLIER_BALANCE_VIEW. | Supplier details |
| `manager/suppliers/{id}/account` | Same bounded account parameters/DTO; fixed date ascending. | SUPPLIERS_VIEW AND SUPPLIER_BALANCE_VIEW. | Supplier statement |

AccountEntry is exactly date (ISO local SQL business timestamp), type, nullable reference, debit, credit, runningBalance. No transaction ID, source document ID/URL, address, phone2, risk score, age bucket or account-status classification is invented. Lists have no active-status query filter. All six sort options are fixed enums; financial sorts are unavailable without the corresponding financial permission.

## Functionality, accounting and exact amounts

- Separate Arabic Customers and Suppliers destinations expose identity/contact/actual active status, server search, sort, refresh, retry, pagination, details and permitted statements.
- Normal list/detail balances are the API's cached current profile balances. Debt views are positive ledger balances and include inactive debtors/creditors. Their aggregate covers **all matching server results**, never the loaded page or a client sum. Credit balances remain signed in normal lists/details rather than being classified as positive debt.
- Customer running effect is **debit minus credit**; positive is receivable by the store and negative is customer credit. Supplier effect is **credit minus debit**; positive is owed to the supplier and negative is credit for the store. Arabic notes explain each independently. No abs(), sign inversion or local financial rule is applied.
- Statements display returned opening/brought-forward balance strictly before the inclusive range start, full-period debit/credit totals and full-period closing balance. Each row's running balance includes prior history. It is explicitly distinct from a page closing balance; the phone never rebuilds a ledger or derives opening/closing from paginated entries.
- Statement types use released enum meanings, with explicit neutral handling of future type codes. Nullable references are omitted when absent; long references wrap at the chosen text scale, and dates/references have LTR direction inside the RTL page. No unsupported source-document navigation is fabricated.
- Money remains exact three-decimal strings, formatted by the accepted KWD formatter without double conversion or arithmetic. Zero, fractional, negative, missing, malformed-present and very large values are tested. Missing/redacted financial values are omitted, never rendered as zero.

## Security and authentication integration

Live `/me` permission codes drive identity and financial gates separately for each party kind; roles grant nothing locally. The released payment-registration permissions are **not** required for party read endpoints. Dashboard debt metrics retain their existing extra CUSTOMER_PAYMENTS / SUPPLIER_PAYMENTS intersections; navigation only appears for an actually visible metric plus the corresponding identity/balance permission.

Unauthorized over-returned balance/aggregate fields are discarded before entering typed models; an unauthorized account cannot be parsed at all. Presentation gates financial values again. Malformed present authorized values fail safely; absent optional balances/aggregate remain absent. Models have private diagnostic strings. New production code performs no response/financial logging, direct SQL access, token handling, local financial persistence, business mutation or credential configuration.

Each controller scopes requests to auth status, user identity, session epoch and sorted live permission codes, plus selected party/query/range/page. Logout, restriction, identity/balance revocation and session changes clear protected models immediately, invalidate generations and reject delayed completions. Financial revocation also removes balance sorts/debt mode before identity-only reload. 403 and missing-object 404 clear affected data immediately, before potentially slow `/me` validation. Centralized 401 refresh/retry-once and terminal signout remain unchanged and are tested through actual inherited auth controllers. There is no autonomous retry loop.

Text and accessibility semantics tests verify that unauthorized and revoked balances cannot remain visible. Safe Arabic failures never display raw SQL, server JSON, financial responses, stack traces or credentials. Unchanged transport no-store and secure-storage behavior is reused.

## State, search, dates, pagination and navigation

The feature follows screen → typed controller → typed repository → existing ApiClient. Immutable party kind carries separate route, permission and accounting labels; shared paging machinery does not combine customer/supplier amounts or permissions.

Search is trimmed, max100 characters, URI encoded, server backed and debounced350ms. Clearing restores default results. Query/sort/debt mode/date/party changes clear the previous context and restart page0 immediately, rejecting stale searches/pages. Same-scope transient refresh retains the prior safe data and identifies it as the previous successful result on error.

Page defaults20, size1–100 and page0–10000 are validated. Refresh and next-page calls are singleflight; failure retains the previous cursor so retry requests the same failed page. Identity overlaps across list pages are deduplicated. Empty/end pages stop further progression. No automatic collection loading or unbounded retries occur.

Statement presets are resolved by the server; no device-clock business-date calculation or prerequisite Dashboard access is used. The first returned range anchors subsequent pages to explicit from/to, including across midnight. Custom dates validate actual calendar dates, 1900–9998 bounds, order and at most366 inclusive days. Changing dates or party invalidates pending first/next pages. Paging validates the range and chronological order, retaining the last explicit full-range header refresh rather than replacing it with page-running values.

The Manager drawer preserves Dashboard, Inventory, Sales/Invoices and Profile/Devices/logout. Customers and Suppliers appear independently when authorized. Dashboard actions open the actual receivables/payables destinations. Stale selection cannot retain a denied destination. Detail → statement → Android route back remains predictable. No edit/delete/payment/POS control was introduced.

## Fresh verification

Toolchain retained: **Flutter3.47.5 / Dart3.13.4**. No packages/platform projects changed.

| Check | Result |
|---|---|
| `dart format --output=none --set-exit-if-changed lib test` | 74 files; 0 changed; exit0 |
| `flutter analyze --no-pub` | No issues found; exit0 |
| `flutter test --no-pub` | **488 passed / 0 failures / 0 skips**; exit0 |
| Inherited Phase1–4 tests, unchanged | **316**: 55 +58 +93 +110 |
| New repository/DTO tests | **82** |
| New state/auth/concurrency tests | **41** |
| New widget tests | **49** |
| Total new default Phase5 tests | **172** |
| Explicit disposable live HTTP suite | **8 passed**, separate from488 |
| Actual Flutter render harness | **18 passed**, separate from488; 72 light/dark PNG captures |
| Git diff / new-file whitespace checks | Clean |
| Index | Empty |

Repository tests cover exact party/account DTOs, field redaction/over-return/malformed financial values, independent permission combinations, sign/full-range semantics, empty results, null references, privacy diagnostics, pagination metadata/IDs, duplicate identities, literal search, all sort mappings, range/timestamp errors, bounded queries and safe HTTP failures.

State tests cover loading/refresh/search clear/debounce, query and party/range stale responses, singleflight/retry/same-page behavior, overlap handling, server date anchoring, identical legitimate account rows, permission/financial revocation, 403/404 immediate clearing, password restriction/logout and central 401 recovery/terminal signout. Widget tests cover both real destinations/details/debt/account views, navigation/back, server search/sorts/custom dates, states/retry/paging, signed values, permission and accessibility redaction, stale navigation and Dashboard actions.

Arabic RTL responsive tests and actual Flutter renders cover **320×700, 390×844, 800×1024, text scale1.5**, both party kinds and list/detail/statement screens. Light and dark representative phone/tablet renders were inspected. Long IDs/references wrap rather than shrink to an unreadable single line; full large signed decimals remain readable. No RenderFlex overflow, clipped financial amount or horizontal page scrolling was observed. Editable search fields retain their normal text scrolling. This is widget/render verification, not native device certification.

Early new-test harness issues were corrected: a MediaQuery wildcard parameter, off-screen retry tap/finder expectations, deprecated semantics inspection and an incorrect assumption that the highest supplier payable must be inactive. Requirements remained unchanged, and the complete final suite passed; inherited tests were not altered or weakened.

### Disposable live HTTP safety and results

`test/live_parties_contract.dart` runs only with explicit disposable-mode URL and runtime password environment variables. It is not discovered by the normal `_test.dart` suite and has no skipped placeholder tests. The ignored external harness reused existing temporary DB helpers and the unchanged packaged API JAR.

Generated SQL credentials were process-environment values only. Before creation, the disposable login was verified to have **no access to real AlMahwarDB**. Only uniquely named disposable business and security/session databases were targeted. Synthetic fixtures were seeded there; the existing released role/permission matrix was used without changing backend code or real data.

Eight fresh tests verified ADMIN/ACCOUNTANT/CASHIER/STOREKEEPER identity and financial intersections, backend field omission, denied financial routes/sorts, literal search, signed customer/supplier credits, summaries including inactive debtors/creditors, independent pages, statement dates, full-history opening/running/closing, empty-range brought-forward balances, controller pagination, restricted credentials, cross-session revocation and GET/no-store responses.

Disposable fixture business-table fingerprints before/after matched. Disposable schemas were **business1.10.0 / API1.0.0**. Temporary API processes were stopped. Final independent read-only cleanup inspection confirmed:

```text
real_business_schema=1.10.0
disposable_business_databases=0
disposable_session_databases=0
temporary_logins=0
permanent_api_security_database=not_provisioned
```

Real AlMahwarDB was not the fixture/API target and was not mutated; its master metadata remained unchanged. The permanent `AlMahwarApiDB` is **not provisioned** in this environment. API schema1.0.0 refers to the freshly checked disposable API DB and unchanged released sources, not an invented permanent-DB result. No provisioning/schema migration was performed.

Generated SQL/application passwords and raw access/refresh credentials were absent from verification logs. Source/security scans found no hardcoded real secret, direct database connection, financial-response logging, mutation transport or monetary floating-point conversion.

### Accepted backend baselines — not freshly rerun

Backend/Desktop/Core/SQL are protected and unchanged. Complete expensive backend suites were not rerun for this Flutter-only feature. Accepted results remain **API637 passed** and **Desktop373 discovered /168 executed /205 skipped /0 failures/errors**. These are distinct from fresh Flutter tests and the eight live HTTP tests above.

## Frozen-contract limits

- There is no active list filter, phone2/address/risk/overdue classification, transaction/source ID, source-document link, export or payment-history mutation endpoint used here; unsupported fields/actions are omitted.
- Normal profile balances and positive-ledger debt projections are different backend views. No reconciliation or company aggregate is derived locally.
- No account-entry ID is exposed. Equal legitimate entries are retained rather than deduplicated by value; offset pages cannot promise absence of overlap under concurrent backend ledger changes. The API does not guarantee a cross-statement/request snapshot. A manual refresh restarts the account and its full-range header; later pages do not silently overwrite that header.
- Native Android/Windows packaging, secure storage on physical devices and deployment HTTPS provisioning remain outside this task; no toolchain/platform workaround was introduced.

## Protected source, refs, stash and docker

**569 protected tracked files** matched preimplementation SHA256 hashes. This includes all inherited tests, Desktop/API/Core/SQL/schema/auth/contract/dependencies/platform files and historical reports. Only the two intended existing navigation/presentation files changed. All previously existing local branches, origin tracking refs, actual remote heads and tag objects matched the saved baseline; only the authorized new local Phase5 branch was added.

| Protected branch / tag | SHA |
|---|---|
| main / origin/main / actual remote | `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b` |
| API Phase5 | `b86e66eb2fe24f672a73324518813909feca7c62` |
| API Phase4 | `1b9a46f260d513bfbf3c6391d98a890a3de00878` |
| API Phase3 | `75bd8d208699261418e97bbf166547820d0742bf` |
| API Phase2 | `32c673406025c6c78f9adae2c61e604c954c1604` |
| API Core adoption | `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99` |
| API development (local) | `5cd2819eb2495123c1a41b5db324c267f389d5ee` |
| Desktop release branch | `5f9e9e0a740279496e3603caf556fbb31f8a5fe3` |
| Flutter Phase1 / separate Desktop redesign branch | `2592d031550ebd33206c41f855e48f2d2c362f27` |
| Flutter Phase2 | `b17b6df4fd5196af8ee156356f749063b4e9b684` |
| Flutter Phase3 | `ae68338e768a33c48f72b0d65caa33c361600d3a` |
| Flutter Phase4 / current HEAD | `464aa6c0da386f710e4db888fe22c746cf026597` |
| v1.0.0 tag object | `7202640ad2f44ef879920cff2d2b08ab13fb6446` |
| v1.0.1 tag object | `8b97ebb60e333522f784e63935cb2ad2b088c627` |

Desktop redesign stash **`df78653663bcbfa5c331a83f2a79bc39d4076f52`** remains present/unchanged, never restored/dropped/staged. Independent backups at `C:/Users/ahmed/.codex/backups/al-mahwar-desktop-login/20261008-3d7c4ed8/` retain exact SHA256:

- login.fxml: `F73F037470594D0BF664D202041D817873F392FC45715C08ED5689E9536297C9`.
- styles.css: `74768798C3228FF15B62B6CD25E96E2EF837D13165C6A151F869879A142D4E06`.

Untracked `docker` is unchanged (empty-file SHA256 `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`). Earlier verification evidence remains ignored and preserved.

Exact final `git status --short`, using Git's ordinary directory grouping:

```text
 M mobile/manager_app/lib/features/dashboard/presentation/dashboard_screen.dart
 M mobile/manager_app/lib/features/dashboard/presentation/manager_shell.dart
?? docker
?? docs/FLUTTER_MANAGER_PHASE5_PARTIES_REPORT.md
?? mobile/manager_app/lib/features/parties/
?? mobile/manager_app/test/live_parties_contract.dart
?? mobile/manager_app/test/party_repository_test.dart
?? mobile/manager_app/test/party_state_test.dart
?? mobile/manager_app/test/party_widgets_test.dart
?? mobile/manager_app/test/support/party_fixtures.dart
```

Untracked files were inventoried individually without staging: all fourteen match the manifest above. `git diff` alone contains only the two modified files. Index remains empty, HEAD unchanged; no commit/push/merge/tag/PR/Phase6 was performed. Stop for human review.

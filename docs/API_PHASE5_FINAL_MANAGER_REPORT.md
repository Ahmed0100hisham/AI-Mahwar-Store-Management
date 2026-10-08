# Phase 5 final Manager reads — implementation and verification report

## Authority discovery (recorded before implementation)

Baseline: `api-phase5-manager-final` from accepted Phase 4 `1b9a46f260d513bfbf3c6391d98a890a3de00878`. No Desktop, Shared Core, schema or dependency changes are authorized.

| Capability | Released authority | Read contract decision |
|---|---|---|
| Invoice header/customer/cashier/status | `Sale`, `SaleDao`, `SaleServiceImpl`, frozen `Sales` schema | Explicit bounded projections; SALES_VIEW at controller and service. Stored DRAFT/POSTED/CANCELLED and payment method/status. Never call number generation. |
| Totals/discount/tax/payment | `Sales` constraints/computed columns; `Sale.recalculate`, `SaleDao` | Return stored subtotal, invoice discount, tax, total, paid, remaining, payment status. The SQL header supports tax although the released Java Sale model omits it; do not recalculate the header through that model or fabricate line tax. Paid/remaining are invoice snapshots, not a payment transaction history or customer balance. |
| Items/historical costs | `SaleItem`, `Sale_Items`, `SaleDao.map`, `SaleServiceImpl.hideIfNeeded` | Stored line totals/whole-line discounts; historical unit costs only with SALES_COST_VIEW, stored invoice gross profit only for POSTED invoices with SALES_PROFIT_VIEW. Draft/cancelled profit is omitted exactly as in SaleDao.map. Never current product purchase cost. |
| Returns/refunds | `ReturnServiceImpl.netUnitValue/settle/buildLines`, `ReturnDao`, `ReportDao.salesRows/profit`, `Sale_Returns` and items | Return values and refunds remain separate stored amounts. Net invoice amount = stored total less stored return value, as in released sales rows. List uses returns through its inclusive end date, matching the report cutoff; detail includes all recorded returns including later dates. Original payment snapshot is not rewritten into a guessed post-return debt. Return items retain original historical costs and already allocated net prices; no allocation is recomputed. |
| Numbering | Sale/Quotation DAO number generators | Stored numbers only. Number generation and its known SQL 1205 issue are out of scope. |
| Quotations/items | `Quotation`, `QuotationItem`, `QuotationDao`, frozen quotation tables | Bounded explicit projections. QUOTATIONS_VIEW. No tax column exists: no fabricated quotation tax. Registered identity and explicitly labeled prospect identity are separate, including nullable-customer records. |
| Lifecycle/expiry | `QuotationStatus`, `Quotation.isPastValidity`, `QuotationServiceImpl.search/findById/expireOverdue` | Exactly DRAFT, SENT, ACCEPTED, REJECTED, EXPIRED, CONVERTED. Validity ends after valid_until (today itself is valid). Core service reads can update overdue open statuses and audit expiry: deliberately bypass those mutating reads. API reports stored status plus pure pastValidity and businessDate; it never performs expiry transitions. |
| Conversion | `QuotationDao` joined converted_sale_id; sale posting and Sales.quotation_id | Report the actually stored linked sale and its real status; ACCEPTED can link a draft and a cancelled linked sale is not proof of conversion. Link additionally requires SALES_VIEW. No conversion endpoint. |
| Daily sales/profit/expenses/cash | Phase 3 `ManagerQueryService`/`ManagerAnalyticsRepository`, released `ReportDao` | Compose existing single-day sales, expenses and cashbox methods without duplicating accounting formulas. Invoice and return activity use their own dates. Profit uses historical sale/return costs; expenses follow their own dates. |
| Current inventory | Phase 3 inventory projection | If included, label as a current snapshot with SQL businessDate, distinct from the requested historical/future summary date. No historical stock/balance fabrication. |
| Receivables/payables | Phase 3 customer/supplier account endpoints and core ledger rules | Existing routes remain authoritative; do not add a second balance formula to daily summary. |
| Authorization | Shared Core `Permission`/`RolePermissions`, Phase 2 live principal/session loading, `SpringSecurityContext` | SALES_VIEW and QUOTATIONS_VIEW for documents; daily DASHBOARD, with existing Phase 3 permissions separately gating every optional section. Role/permission claims in JWT never authorize access. |
| Dates/decimal/paging | Phase 3 `ManagerDateRange`, `ManagerPage`, `ManagerResponses`, core MoneyUtil/QuantityUtil | SQL server business-local clock (deployment aligned to Kuwait); inclusive dates use half-open timestamp predicates; three-decimal strings; page 0/default 20/max 100 and strict deterministic sort allowlists. Detail item/return collections are independently bounded and pageable. |

No unresolved READ CONTRACT GAP was found in these read capabilities. Differences above are explicit contract decisions grounded in released code/schema, not changes to business rules. Final evidence and readiness assessment follow after verification.

## Final numbered report

1. **Status:** implementation and final verification complete; ready for review, unstaged/uncommitted. No deployment or Flutter work.
2. **Branch:** `api-phase5-manager-final`, created directly from accepted Phase 4.
3. **Starting/current HEAD:** both `1b9a46f260d513bfbf3c6391d98a890a3de00878`.
4. **Phase 4 local/origin/actual remote:** `1b9a46f260d513bfbf3c6391d98a890a3de00878`.
5. **Phase 3 local/origin/actual remote:** `75bd8d208699261418e97bbf166547820d0742bf`.
6. **Phase 2 local/origin/actual remote:** `32c673406025c6c78f9adae2c61e604c954c1604`.
7. **main/origin/main/actual remote:** `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b`. Other protected refs/tags are listed below; all unchanged.
8. **Desktop version:** 1.0.1, unchanged.
9. **Shared Core:** released 1.0.1, unchanged, packaged binary identical to installed released classifier.
10. **Business schema:** real AlMahwarDB reports 1.10.0 in a read-only schema audit; frozen schema and disposable tests unchanged.
11. **API schema:** unchanged 1.0.0, verified in disposable security catalogs and packaged readiness. Permanent AlMahwarApiDB is not provisioned on this development server; provisioning the existing schema is a separate deployment prerequisite, not performed here.
12. **Authority discovery:** the map above was recorded before source implementation; Sale/Quotation/Return models, DAOs/services, frozen SQL and Phase 3 calculations were inspected.
13. **READ CONTRACT GAP:** none unresolved. Stored invoice tax absent from the Java Sale model and mutating Desktop quotation expiry reads are explicitly handled/documented.
14. **Invoice list:** GET `/api/v1/manager/invoices`.
15. **Invoice detail:** GET `/api/v1/manager/invoices/{id}`.
16. **Invoice filters:** inclusive dates/presets, literal invoice/customer code/name/phone search, positive customerId, released paymentMethod and DRAFT/POSTED/CANCELLED status.
17. **Invoice paging/sort:** page 0..10000, default size 20/max 100; allowlisted date/number/total asc/desc with sale ID tie-breaker. Detail item pages and independent return pages are bounded.
18. **Returns:** stored return value/refund distinct; released total-minus-return-value net amount. List cutoff is inclusive range.to, detail includes all recorded returns. Verified no/partial/multiple/later/full returns and fractional quantities. Original paid/remaining snapshots are not relabeled post-return debt.
19. **Historical costs/profit:** stored historical unit/header costs; no product current-cost substitution. Stored grossProfit is POSTED-only exactly like SaleDao.map, before returns; drafts/cancelled omit it.
20. **Invoice authorization/redaction:** SALES_VIEW at controller/service; SALES_COST_VIEW / SALES_PROFIT_VIEW separately gate cost/profit columns/keys. Cashier receives neither; storekeeper denied.
21. **Quotation list:** GET `/api/v1/manager/quotations`.
22. **Quotation detail:** GET `/api/v1/manager/quotations/{id}`; paged items in fixed ID order.
23. **Real statuses:** DRAFT, SENT, ACCEPTED, REJECTED, EXPIRED, CONVERTED; all tested.
24. **Expiry:** core pure validUntil-before-SQL-businessDate rule; today remains valid, null expiry is not past. GET never expires or audits a quotation.
25. **Tracking:** stored lifecycle state and independent pastValidity, plus stored sent/decided times. No invented ACTIVE/CANCELLED quotation status.
26. **Conversion:** actual stored sale link/status; ACCEPTED may link DRAFT or CANCELLED, CONVERTED links a posted sale. Link additionally requires SALES_VIEW. These cases are tested; no conversion write route.
27. **Daily summary:** GET `/api/v1/manager/daily-summary?date=yyyy-MM-dd`, default SQL business today.
28. **Daily metrics:** optional released sales/returns/count/average/profit, expenses, cashbook; current inventory snapshot separately dated. No invented receivable/payable history.
29. **Formulas/reuse:** delegates to Phase 3 ManagerQueryService sales/expenses/cashbox/inventory. POSTED sales and returns use their own dates; profit uses historical costs, then dated expenses; cash includes released payment methods.
30. **Daily redaction:** DASHBOARD plus full existing section permissions. Cashier sales omit profit/expenses/cash/inventory; storekeeper sees current inventory only; accountant has financial sections but no inventory section. Unauthorized sections are absent.
31. **Authentication:** all five routes tested for missing/invalid/expired/revoked/disabled/reset credentials, restricted must-change sessions and live downgrade. Packaged HTTP verifies authentication, must-change and disabled-user denial too.
32. **Authorization:** controller and service guards, live Shared Core matrix intersection; forged broader principal authorities do not grant access. JWT role/permission claims never authorize.
33. **DTOs:** purpose-built records, bounded nested pages; no Desktop entity/JDBC row serialization, credential state, request UUIDs or free-form operational notes.
34. **Money:** exact three-decimal KWD strings through core MoneyUtil; zero/negative/large/rounding-edge checks.
35. **Quantity:** exact three-decimal strings through QuantityUtil; fractional SQL fixtures and rounding-edge checks.
36. **Dates:** ISO dates/local business timestamps, UTC security instants; inherited SQL-local Kuwait deployment clock and half-open timestamp predicates preserved. Midnight endpoints, prior/future/default dates tested.
37. **Paging:** existing PageResponse and ManagerPage convention retained; no breaking Phase 3/4 refactor. Inherited own-session history stays an unpaginated array, documented for retention/growth monitoring.
38. **Sort safety:** strict field/direction allowlists and unique tie-breakers. Injection-like sort strings rejected before persistence.
39. **Search:** bound, literal escaped LIKE values; `%`, `_`, `[`, quote, `--`, semicolon, Arabic/mixed text and document numbers tested for both document types.
40. **SQL injection:** enum/ID/range/page validation, parameterized filters and source/HTTP/SQL checks passed; client SQL identifiers are never interpolated.
41. **Performance:** explicit projections, SQL aggregates, bounded pages, fixed business query counts, no per-row DAO calls/Java full-table aggregation/NOLOCK. Two representative document SHOWPLANs captured against frozen indexes. Broad search/deep-page production measurement remains necessary; no index added.
42. **Caching:** new controller included in existing Manager advice; no-store verified on MockMvc, SQL and packaged successful responses.
43. **Errors:** existing safe Arabic ApiError/requestId contract, 400/401/403/404/405 and safe outage handling. Not-found and internal connection details verified; no SQL/host/credential text returned.
44. **OpenAPI:** development-only defaults preserved; all five routes document bearer/permissions, filters, bounded paging, date/money semantics, redaction and errors; real packaged specification exported.
45. **Freeze document:** `docs/API_MANAGER_CONTRACT_V1.md` contains transport/DTO/lifecycle/permission contracts and all Phase 1–5 endpoint operations. Proposed freeze requires review; HTTPS only for Flutter, no direct SQL.
46. **Inventory:** programmatically exported from packaged OpenAPI, including exact METHOD/PATH pairs; no accidental business-write/POS mappings.
47. **Total API:** 45 METHOD/PATH operations across 43 unique paths.
48. **Manager total:** 34 METHOD/PATH operations across 32 unique paths.
49. **Phase 5:** exactly five GET operations.
50. **GET/mutation breakdown:** API 34 GET / 11 existing mutations; Manager 29 GET / five existing identity mutations. The 24 Manager business reads (19 inherited + five new) are GET-only; audit is also GET-only.
51. **Production config:** empty bundled DB/JWT values, distinct credentials/catalogs, non-sa enforcement, production encrypted/certificate-valid connections, wildcard CORS rejection, API init false and spring.sql.init never, dev-only docs defaults and safe logging/request IDs verified. Environment names without values documented; no deployment.
52. **Health/readiness:** liveness UP; readiness safe READY/NOT_READY for both required DBs/schemas and full Manager business table dependencies. Added missing-quotation-table privacy test; packaged readiness/liveness green.
53. **Phase 5 unit/HTTP:** 67 passed: ManagerDocumentApiTest 58 and ManagerDocumentRulesTest 9; zero failures/errors/skips.
54. **Phase 5 SQL:** 66 passed, including packaged HTTP/route inventory; zero failures/errors/skips.
55. **Real HTTP:** launched packaged JAR with credentials in process environment against disposable databases. Verified all five routes, four-role access, cost/profit redaction, search/sort/paging, validation/404, auth/must-change/disabled denial, no-store, safe health and dev OpenAPI. Packaged document class bytes match compiled classes. Process always terminated in finally.
56. **Permission matrix:** ADMIN/ACCOUNTANT/CASHIER/STOREKEEPER tested against every new endpoint at HTTP and SQL layers; field/section checks and forged principal tests passed.
57. **Invoice scenarios:** tax 0.125, subtotal 14.000, invoice discount 2.000, total 12.125, historical cost 5.000; fractional 2.500 line with line discount. Partial cutoff returned 1.559/refund 0.000; all-time multiple returns 4.676/refund 1.117/net 7.449; full return net 0.000. Draft/cancelled profit omitted.
58. **Quotation scenarios:** all six statuses, stored totals, Arabic/literal searches, expiry today/past/null, nullable registered customer/prospect, stable pages and actual draft/cancelled/posted links passed without business mutation.
59. **Daily scenarios:** controlled activity day, return-only negative sales/profit, empty future day, default SQL date, both midnight boundaries and independent permission filtering passed.
60. **Phase 4 regression:** 117 unit/HTTP + 41 SQL = 158 passed. Administration/audit, last-active-admin/concurrency, reset/disable/enable/role/session protections and no permanent deletion remain green.
61. **Phase 3 regression:** 164 unit/HTTP + 32 SQL = 196 passed; all 19 original read routes, formulas and redaction green. Its inventory assertion excludes new Phase 5 routes, which have their own complete inventory.
62. **Phase 2 regression:** real login/refresh rotation/replay/logout/all/session revoke/password/must-change/pwv/fingerprint/disabled/outage/readiness/concurrency coverage passed, including all 33 session SQL tests.
63. **Phase 1 regression:** inherited Products/core permission/sorting tests and all 10 Products SQL tests passed. Combined inherited Phase 1/2/foundation regression stays 150.
64. **Desktop/core:** `mvn -B -o test` passed: 373 discovered, 168 executed, 205 existing opt-in skips, zero failures/errors. No Desktop/core edits.
65. **Complete API:** final `mvn -B -o -f api/pom.xml verify -Dalmahwar.it=true` passed 637 tests, zero failures/errors/skips: inherited 504 plus Phase 5 133 (453 non-SQL + 184 SQL).
66. **Secret scan:** all 129 API source/resource/test/documentation files scanned; no raw JWT/refresh/private-key findings or deployable production credential assignments. Fixed passwords are synthetic disposable-test fixtures only.
67. **Runtime evidence scan:** final API/packaged logs and JUnit XML contain no temporary SQL username/password, admin bootstrap password, raw JWT/refresh credential or Authorization header values. Credential fingerprints/private keys are not exposed in DTOs or evidence.
68. **Package:** `api/target/almahwar-api-0.1.0-SNAPSHOT.jar`, SHA256 `7290DA3BCD9BFBB5CBAC80ADD7C5EC4E2F6CCE53BE94BDC5809CB761AFF51AA4`; released Shared Core 1.0.1, 35 Phase 3 / 18 Phase 4 / 17 Phase 5 class entries. Five credential/JWT defaults empty; no local config, scripts, logs, secrets or private keys.
69. **JavaFX/JPA:** absent as libraries and forbidden class references in API/core entries; Hibernate ORM/Flyway/Liquibase absent.
70. **Disposable cleanup:** final master audit reports zero disposable business/security databases; cleanup also runs in shell finally on failures.
71. **Temporary login cleanup:** zero temporary phase test logins; tests used a unique non-sa login, removed in finally. sa was used only for isolated master bootstrap/cleanup and read-only environment audit, never application tests.
72. **Real AlMahwarDB safety:** no business data mutation or restoration; test and packaged configurations explicitly target unique disposable catalogs. Real master metadata unchanged; real schema audited read-only. Disposable business fingerprints unchanged after each Phase 5 test (authentication/security writes retain their established separate scope).
73. **Desktop protected integrity:** root pom.xml, src/, database/, config/ and Desktop resources/release files unchanged against main. No Phase 5 tracked changes outside API source/tests and the four documents. Root README.md/.gitignore already differ from main in the accepted API baseline; Phase 5 changes neither. Protected refs/tags unchanged; docker remains empty, untouched and untracked.
74. **Schema changes:** none; existing frozen scripts are used solely to set up disposable test catalogs. Readiness table list adds no DDL or migration.
75. **Dependency changes:** zero; both Maven POMs unchanged.
76. **Read boundary:** five guarded GETs; pure document projections and inherited report reads; architecture and business fingerprint checks passed. No new business mutation SQL.
77. **Mobile POS:** none; no sale/quotation/payment/purchase/return/expense/stock creation or edit routes.
78. **Flutter:** not started; no Dart/pubspec/client implementation.
79. **SQL 1205:** known document-number generation deadlock unchanged; number generators are never invoked by Phase 5.
80. **Backend readiness:** requested Manager v1 API capabilities complete and verified; ready for contract/code review. Production provisioning/TLS/configuration remains a separate prerequisite, not an assertion of a deployed service.
81. **Missing Flutter requirement:** no missing backend API capability identified. Carry documented limits into client design: stored quotation status versus pastValidity, original invoice payment snapshots, list/detail return cutoff, separately dated current inventory, read consistency and inherited session-history growth.
82. **Changed files:** exactly 17 intended source/test/docs files, listed below. Health dependency expansion, existing advice/access integration and Phase 3 inventory exclusion are the only inherited-code/test adjustments; all are explicitly Phase 5 integration work.
83. **Final Git status:** exact output below; only the intended unstaged changes plus pre-existing `?? docker`. target evidence ignored/untracked and excluded from the review set; no credentials/logs/scripts staged.
84. **Commit:** none; index empty and HEAD unchanged.
85. **Push:** none; no Phase 5 remote publication performed.
86. **Merge/tag/rebase/PR:** none. Previous API branches, main and tags untouched; no further phase started.

## Protected refs and evidence

Local/origin/actual remote were independently checked for main and Phase 2/3/4/core adoption. `api-core-adoption` remains `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99`; local `api-development` remains `5cd2819eb2495123c1a41b5db324c267f389d5ee`; local `desktop-1.0.1-core` remains `5f9e9e0a740279496e3603caf556fbb31f8a5fe3`. Annotated tag objects v1.0.0 `7202640ad2f44ef879920cff2d2b08ab13fb6446` and v1.0.1 `8b97ebb60e333522f784e63935cb2ad2b088c627` remain unchanged locally/remotely. Docker SHA256 remains `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`.

Ignored evidence: root `target/phase5-final-api.log`, `phase5-final-package-build.log`, `phase5-desktop.log`, `phase5-final-safety.json`, `phase5-tests.json`, `phase5-final-package.json`, `phase5-core-integrity.json`, `phase5-protected-refs.json`, `phase5-source-audit.json`, `phase5-all-source-secret-scan.json`, `phase5-evidence-secret-scan.json`, `phase5-permanent-schema-audit.txt`, `phase5-route-inventory.json`; API `target/phase5-packaged-http.log`, `phase5-openapi.json`, `phase5-route-inventory.json`, `phase5-query-plans.xml`, and standard JUnit reports. These are generated evidence, not intended commit files.

The final run follows the completed first 634-test pass and verifies the final POSTED-only profit and expanded readiness corrections, adding three tests. No source/test code changed after the final rebuild/637-test verification. Documentation records final results afterward. Whitespace check passed.

## Exact final status / intended file manifest

All entries below except `docker` are the intended 17-file review set. No entries are staged.

```text
 M api/src/main/java/com/almahwar/api/health/SchemaCompatibilityChecker.java
 M api/src/main/java/com/almahwar/api/manager/ManagerAccess.java
 M api/src/main/java/com/almahwar/api/manager/ManagerCacheAdvice.java
 M api/src/main/java/com/almahwar/api/manager/ManagerErrorAdvice.java
 M api/src/test/java/com/almahwar/api/ManagerSqlServerIntegrationTest.java
 M docs/API_ARCHITECTURE.md
?? api/src/main/java/com/almahwar/api/manager/ManagerDocumentController.java
?? api/src/main/java/com/almahwar/api/manager/ManagerDocumentFilter.java
?? api/src/main/java/com/almahwar/api/manager/ManagerDocumentRepository.java
?? api/src/main/java/com/almahwar/api/manager/ManagerDocumentResponses.java
?? api/src/main/java/com/almahwar/api/manager/ManagerDocumentService.java
?? api/src/test/java/com/almahwar/api/ManagerDocumentApiTest.java
?? api/src/test/java/com/almahwar/api/ManagerDocumentSqlServerIntegrationTest.java
?? api/src/test/java/com/almahwar/api/manager/ManagerDocumentRulesTest.java
?? docker
?? docs/API_MANAGER_BACKEND_READINESS.md
?? docs/API_MANAGER_CONTRACT_V1.md
?? docs/API_PHASE5_FINAL_MANAGER_REPORT.md
```

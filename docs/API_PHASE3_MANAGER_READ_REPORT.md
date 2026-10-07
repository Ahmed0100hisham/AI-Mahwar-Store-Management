# API Phase 3 — Manager read APIs

Implementation branch `api-phase3-manager-read`, from Phase 2 `32c673406025c6c78f9adae2c61e604c954c1604`.
Business schema stays 1.10.0; API session schema stays 1.0.0. Desktop/core are frozen.

## Discovery map (established before implementation)

| Concept | Tables / released authority | Desktop definition | API / permission |
|---|---|---|---|
| Dashboard | DashboardDao, DashboardStats, DashboardService.Metric | SQL server accounting date; per-metric visibility | `/manager/dashboard`, DASHBOARD; extra balance gating |
| Sales | Sales, Sale_Returns; ReportDao.salesSummary | POSTED invoices by sale_date; returns by return_date; net = gross − returns; average = gross / invoice count | `/manager/sales/summary`, REPORTS_VIEW + REPORTS_SALES |
| Profit/cost | ReportDao.profit | historical Sales.cost_total and Sale_Returns.cost_total; reverse returned revenue minus returned cost; subtract Expenses for net profit | profit fields require REPORTS_PROFIT |
| Trend | DashboardDao chart / ReportDao sales definitions | Dashboard chart is gross; Manager trend explicitly returns gross, returns and net separately | `/manager/sales/trend`, bounded API aggregate with report permissions |
| Best sellers | ReportDao.bestSellers | net quantity after period returns; line revenue less rounded invoice-discount share; historical cost | `/manager/sales/top-products`; conservative REPORTS_SALES; profit gated |
| Slow moving | ReportDao.slowMovingRows/count | active stock > 0, last POSTED sale older than server date − days, or never sold | `/manager/sales/slow-products`, inventory report permission |
| Expenses | Expenses; ReportDao.expensesByCategory | recorded amounts by expense_date and existing ExpenseCategory | `/manager/expenses/summary`, REPORTS_VIEW + REPORTS_EXPENSES |
| Cashbox | Cash_Transactions; ReportDao.cashSummary | all payment methods; opening before range, IN − OUT, closing | `/manager/cashbox/summary`, REPORTS_VIEW + REPORTS_CASHBOX + CASH |
| Inventory | Products, Units; InventoryService / ReportDao.inventoryTotals | current quantity; value = sum rounded quantity × current purchase_price | summary/low-stock, INVENTORY + inventory report permissions; cost PRODUCT_COST |
| Low stock | DashboardDao / ReportDao.lowStockWhere | is_active = 1 AND quantity <= minimum_stock | bounded searchable list with stable allowlisted sorting |
| Product detail | ProductServiceImpl.findById | view-only users see active products only; cost hidden without PRODUCT_COST | `/manager/products/{id}`, PRODUCTS_VIEW or PRODUCTS |
| Movement | Stock_Movements; ReportDao.movementRows / StockLedger | authoritative unified history, signed quantities, before/after | `/manager/products/{id}/movements`, inventory permissions; cost gated |
| Customer profiles | CustomerServiceImpl.findById / CustomerDao | bounded API list; cache balance updated atomically with ledger | `/manager/customers`, detail; CUSTOMERS_VIEW, balances CUSTOMER_BALANCE_VIEW |
| Supplier profiles | SupplierServiceImpl.findById / SupplierDao | same; storekeeper has profile access but no balance access | `/manager/suppliers`, detail; SUPPLIERS_VIEW, balances SUPPLIER_BALANCE_VIEW |
| Receivables/payables | Account_Ledger; ReportDao.balanceRows / PartyType | customer SUM(debit-credit), supplier SUM(credit-debit), positive debts only, including inactive parties | `/customers/receivables`, `/suppliers/payables`; profile + balance permissions |
| Accounts | AccountLedgerDao / AccountLedger.statement | opening brought forward, running balance over whole party history | paged `/customers/{id}/account`, `/suppliers/{id}/account`; API projection required because core statement is unbounded |

Sales DRAFT/CANCELLED have no effect; CANCELLED means abandoned draft, not a reversed posted invoice.
Returns have no draft/status lifecycle and are included as recorded, including a return of an older-period sale.
No guessed accounting formulas or schema changes are needed. Mixed-unit stock quantities will not be summed.
Desktop dashboard best-seller cards use gross line totals; Manager uses the more complete released **report**
definition, including returns and invoice discounts. Low-selling is represented by the authoritative slow-moving
report, not an invented ascending sales ranking. Recent audit/activity blobs and additional financial ratios are deferred.

The dashboard preserves core **monthly** profit and expenses rather than labelling them as daily. Daily profit
and expense amounts are available through the authorized summary endpoints with `period=today`. A dedicated
recent-activity feed, physical-cash-only balance, mixed-unit inventory sum, margin/tax policy and bottom-seller
ranking are not introduced: they need a separately selected display/accounting rule or narrower privacy-safe
projection. Reference-data lists, dedicated barcode lookup and extra mobile features are also deferred.

## Endpoint contract

All paths below have prefix `/api/v1/manager`, require a live Phase 2 bearer session and use GET.
Controller checks and direct query-service checks both enforce the following permission sets:

| Key | Released permissions required |
|---|---|
| D | DASHBOARD; each dashboard metric is also independently filtered |
| S | REPORTS_VIEW + REPORTS_SALES |
| I | INVENTORY + REPORTS_VIEW + REPORTS_INVENTORY |
| E | REPORTS_VIEW + REPORTS_EXPENSES |
| B | CASH + REPORTS_VIEW + REPORTS_CASHBOX |
| P | PRODUCTS_VIEW or PRODUCTS |
| C | CUSTOMERS_VIEW |
| CA | CUSTOMERS_VIEW + CUSTOMER_BALANCE_VIEW |
| V | SUPPLIERS_VIEW |
| VA | SUPPLIERS_VIEW + SUPPLIER_BALANCE_VIEW |

`R` below means the common date-range input; `L` means common pagination/search/sort input.
Response names refer to records in `ManagerResponses`. No entire database row is serialized.

| GET path | Permission | Parameters | Response and authority |
|---|---|---|---|
| `/dashboard` | D | none | Dashboard: businessDate and filtered metrics from core DashboardDao |
| `/sales/summary` | S | R, max 366 days | Sales: grossSales, returns, netSales, invoiceCount, returnCount, averageInvoice, optional Profit; ReportDao |
| `/sales/trend` | S | R; grouping=daily/weekly/monthly | Trend: range, grouping, ordered zero-filled buckets of gross/returns/net/invoice count; SQL aggregate of report events |
| `/sales/top-products` | S | R, max 366; limit=20, 1..100 | TopProducts: code/name/unit, gross/returned/net quantities, netRevenue, optional grossProfit; ReportDao.bestSellers |
| `/sales/slow-products` | I | L; days=30, 1..3650 | Page of SlowProduct: code/name/unit/quantity, lastSale, nullable daysSinceLastSale, optional inventoryValue; core count, stable API page of identical rules |
| `/expenses/summary` | E | R, max 366 | Expenses: total/count and all 9 released categories, including zero categories; ReportDao |
| `/cashbox/summary` | B | R, max 366 | Cashbox: openingBalance, totalIn, totalOut, netMovement, closingBalance; ReportDao |
| `/inventory/summary` | I | none | Inventory: activeProducts, lowStockProducts, outOfStockProducts, optional inventoryValue; bounded result SQL aggregate |
| `/inventory/low-stock` | I | L | Page of StockProduct: id/code/name/unit/quantity/minimumStock, optional purchaseCost |
| `/products/{id}` | P | positive id | ProductDetail: id/code/barcode, Arabic/English names, category/brand/unit, salePrice, quantity/minimumStock, lowStock/active, optional purchaseCost; core service |
| `/products/{id}/movements` | I | positive id, R max 366, L without q | Page of Movement: date/type/Arabic typeName, signed quantity, before/after, optional unitCost; core unified stock history |
| `/customers` | C | L | PartyList: page of minimal profiles; cached balance only with CA |
| `/customers/receivables` | CA | L | PartyList plus totalOutstanding of all matching positive ledger debts, including inactive customers |
| `/customers/{id}` | C | positive id | Party: id/code/name/phone/area/active, optional cached balance; core service |
| `/customers/{id}/account` | CA | positive id, R max 366, L without q | Account: partyId/range, opening, totalDebit/totalCredit, closing and entries page; core opening plus bounded window projection |
| `/suppliers` | V | L | PartyList; cached balance only with VA |
| `/suppliers/payables` | VA | L | PartyList plus totalOutstanding of all matching positive ledger debts, including inactive suppliers |
| `/suppliers/{id}` | V | positive id | Party; optional cached balance; core service |
| `/suppliers/{id}/account` | VA | positive id, R max 366, L without q | Account; same structure with supplier sign convention |

`PageResponse` contains `items`, zero-based `page`, `size`, `totalItems`, `totalPages`.
Party lists wrap this under `page`; account statements wrap it under `entries`.
An account entry contains date, released entry type, business reference number, debit, credit and runningBalance;
internal reference IDs, notes, users and security/audit blobs are excluded.
The existing `GET /api/v1/products` is unchanged. POST on all 19 Manager paths is rejected with 405.

## Role and field contract

These results derive from released RolePermissions, not new role-name checks in implementation.

| Endpoint group | ADMIN | ACCOUNTANT | CASHIER | STOREKEEPER |
|---|---|---|---|---|
| Dashboard | filtered allow | filtered allow | filtered allow | filtered allow |
| Sales summary/trend/top | allow | allow | allow, profit omitted | deny |
| Slow stock/inventory/movements | allow | deny | deny | allow |
| Expenses/cash book | allow | allow | deny | deny |
| Product detail | allow | allow | allow, purchaseCost omitted | allow |
| Customer profiles | allow | allow | allow, balance omitted | deny |
| Customer receivables/account | allow | allow | deny | deny |
| Supplier profiles | allow | allow | deny | allow, balance omitted |
| Supplier payables/account | allow | allow | deny | deny |

Cost/value fields require PRODUCT_COST; profit fields require REPORTS_PROFIT. Top-product revenue is available
to sales-report viewers, while historical-cost profit is omitted for cashiers. Balance sorting is denied with
400 when the corresponding balance permission is absent, preventing an ordering side channel. Core product
detail hides inactive products unless PRODUCTS is held. Customer/supplier profiles include inactive parties,
matching core detail behavior. Receivables/payables deliberately retain inactive debtors/creditors.

Dashboard emits only allowed metric entries:

| Metric | Meaning | Released visibility, plus conservative API gates |
|---|---|---|
| TODAY_SALES | today net sales; gross and returns also supplied | SALES_VIEW or FINANCIAL_REPORTS |
| MONTH_SALES | whole calendar-month net sales; gross/returns supplied | FINANCIAL_REPORTS |
| TODAY_INVOICES | count of today's POSTED invoices | SALES_VIEW or FINANCIAL_REPORTS |
| NET_PROFIT | whole calendar-month profit after returns and expenses | FINANCIAL_REPORTS plus REPORTS_PROFIT |
| EXPENSES | whole calendar-month recorded expenses | EXPENSES |
| CASH_BALANCE | all recorded IN minus OUT, every payment method | CASH |
| RECEIVABLES | sum of positive cached customer balances | CUSTOMER_PAYMENTS plus CA |
| PAYABLES | sum of positive cached supplier balances | SUPPLIER_PAYMENTS plus VA |
| LOW_STOCK | active product count at/below minimum | INVENTORY or PRODUCTS |

Each metric value is a decimal string for money and an integer string for counts. Unauthorized metrics are
absent. Cashier's existing CUSTOMER_PAYMENTS grant does not grant customer balances through this API.

## Financial and inventory definitions

* **Sales:** POSTED header `total_amount`, after stored discounts/taxes, selected by sale_date. DRAFT and
  CANCELLED are excluded. Return amounts are selected independently by return_date, including returns of
  older sales. Net sales = gross minus recorded return value. Return-only periods may be negative. Average
  invoice = gross / POSTED invoice count, HALF_UP to 3 decimals; zero when no invoices.
* **Profit:** historical header cost, never current product purchase price. Gross after returns = sales value
  minus historical sales cost minus (return value minus returned historical cost). Net subtracts recorded
  expenses for that range. No invented payment-based revenue, margin ratio or tax-exclusion policy.
* **Top products:** released report ranks net quantity descending, net revenue descending, then name. Net
  quantity = period sold quantity minus period returned quantity. Revenue uses stored line totals minus the
  invoice discount's rounded proportional line share, then subtracts returned line totals. Profit uses stored
  line historical unit_cost. Header tax is not allocated to products by this released report; rounded discount
  shares and header tax mean product totals need not equal the header sales summary. This is preserved,
  documented behavior, not a new allocation formula. Exact ties follow the core report's ordering.
* **Slow stock:** active quantity > 0, never sold or latest POSTED sale strictly before SQL business today
  minus `days`. Returns do not reset last-sale date. Never-sold first, then oldest sale/name/product ID;
  adding the final unique ID preserves eligibility and stabilizes offset pages for duplicate names. No guessed
  bottom-seller metric; returned-only and new products make such a metric ambiguous without a chosen rule.
* **Expenses:** sum recorded amount by expense_date and released ExpenseCategory, without payment-method
  or imaginary status filtering. Category total/count reconcile with the summary.
* **Cash book:** Cash_Transactions across all payment methods, including KNET. Opening is all IN minus OUT
  before `from`; range IN/OUT and net movement produce closing. It is the Desktop cash book, not a count
  of physical banknotes or only payment_method=CASH. Opening necessarily aggregates earlier history.
* **Inventory:** active products only. Low stock includes quantity <= minimum_stock, including zero at a
  zero minimum. Out of stock is quantity <= 0. Current valuation sums each quantity * purchase_price
  rounded to DECIMAL(18,3), as core ReportDao does. No mixed-unit quantity grand total is returned.
* **Parties:** customer positive balance means the customer owes us: SUM(debit-credit); supplier positive
  means we owe the supplier: SUM(credit-debit). Profile/detail and dashboard use core cached balances,
  updated atomically with released ledger operations. Receivables/payables use authoritative ledger sums,
  positive balances only, including inactive parties. Existing cache drift is not repaired by a read API.
* **Statements:** opening is core balanceBefore; period debit/credit and closing retain PartyType signs.
  Running balance is computed before range filtering/paging, so page 1 does not restart at zero. The SQL
  window reads one party's earlier history through range end; it does not load whole history into Java.
* **Movement:** released Stock_Movements is authoritative. Signed quantity, balance_after and before =
  after - signed quantity are preserved at 3 decimals; unit_cost is historical and permission-sensitive.
  No guessed union of purchase/sale documents and no internal audit fields.

## Input, clock, precision and error contract

* All business dates are ISO `yyyy-MM-dd`, inclusive. SQL uses `>= from midnight AND < to+1 midnight`
  on raw indexed columns. The authoritative clock is SQL Server SYSDATETIME, matching Desktop. SQL business
  timestamps have no offset; operate the business SQL server in Kuwait business time. Client/API host time
  does not redefine accounting days. A SQL server configured for another zone follows that zone until its
  operator corrects deployment; this phase performs no historical conversion or server setting changes.
* `period` is `today` (default), `this_week` (Sunday through today), `this_month` (full calendar month), or
  `custom` with both `from` and `to`. Explicit from/to can omit period. Preset plus explicit dates, partial,
  inverted, invalid or oversized ranges receive 400. Supported dates are 1900-01-01..9998-12-31.
* Summary/top/expense/cashbook/movement/account ranges max 366 inclusive days. Daily trend max 366;
  weekly/monthly max 1096. Buckets are chronologically ordered and zero-filled; a bucket's date is its
  Sunday/month start and can precede `from`; amounts still cover only requested dates. No silent truncation.
* Lists: `page=0..10000`, `size=1..100`, defaults 0/20, matching Phase 1. Top products use bounded limit
  1..100 rather than an unbounded list; trend and category lists have fixed bounds.
* `q` max 100 characters before trimming. Shared BaseDao.likeContains handles literal `%`, `_`, `[`;
  prepared NString values preserve Arabic and quotes. Contains search follows released SQL collation.
* Allowlisted `sort=field[,asc|desc]` only. Low stock: name/code/quantity/minimumStock, default quantity asc.
  Parties: name/code/balance (balance requires permission), default name asc; outstanding defaults balance desc.
  Slow stock accepts only `lastSale,asc`; movements only `date,desc`; accounts only `date,asc`. The last two
  reject q. Lists add unique IDs as tie-breakers; core movement order already has its key. Arbitrary SQL sort
  expressions are rejected before data access. Public field names/directions are case-sensitive.
* BigDecimal throughout; KWD uses exact 3-decimal strings via core MoneyUtil. QuantityUtil preserves released
  DECIMAL(18,3) quantity precision, including fractional and signed movement values. No float/double math.
* Errors retain ApiError/request ID: 400 filters, 401 missing/invalid/expired/revoked/disabled/stale credentials,
  403 permission or must-change-password, 404 missing/hidden entities, 405 mutation methods, 503 connection
  unavailable. Core-wrapped SQLState 08 / connection exception failures during Manager queries also map to
  503; other SQL failures map to safe 500. No SQL text, driver message, stack or credentials reaches clients.
* Responses are `Cache-Control: no-store`. OpenAPI annotations describe bearer, dates, bounds, exact strings,
  responses and permission-sensitive fields. Existing dev-only Swagger/OpenAPI configuration is unchanged.

## Query design, plans and consistency

Controller -> authorized query service -> core DAO/service or API read repository -> shared ConnectionSource.
No JPA, transaction wrapper, dependency addition, schema DDL, SELECT *, Java row-by-row business aggregation,
or per-result repository loop. Trend aggregates events in SQL and only fills a bounded number of empty buckets
in Java. Count/page are separate statements. Profile lists project only permitted fields; product cost columns
are absent from low-stock/valuation SQL when cost permission is absent. Core reports can read historical cost
internally, but DTOs omit unauthorized fields.

Actual SQL Server estimated plans were captured with SHOWPLAN_XML on the disposable released schema in
`api/target/phase3-query-plans.xml` (ignored verification evidence). The stock aggregate used
IX_Products_active_stock; sales used IX_Sales_status_date and, for this small fixture's chosen plan, an additional
UX_Sales_quotation access; customer opening used IX_Account_Ledger_customer. Existing date/party indexes
also support returns, expenses, cash and stock history. These are representative plan inspections, not a
production-sized benchmark or a promise that every aggregate seeks rather than scans. Current inventory,
current debt and historical opening balances inherently inspect their relevant population/history.

**No index is applied or demonstrated necessary by these small fixtures.** Before rollout, capture actual
plans/IO/latency with realistic data. If lookups dominate, evaluate covering return-date totals/cost, sale-item
product/sale history and category-amount expense-date indexes; these are conditional candidates, not justified
production migrations. Contains LIKE cannot generally seek ordinary name indexes; measure first before
considering a separately approved search design. Large current-debt aggregation and deep offset pages also
need workload measurement. Page/range bounds limit returned work but do not guarantee constant execution time.

Released connection isolation/autocommit is unchanged. No NOLOCK, serializable/snapshot hint or new business
transaction is added. A response comprising separate sales/profit, count/page or opening/totals/history reads
may observe normal concurrent commits; it is not a cross-query snapshot. Even core dashboard's one statement
retains its released isolation. Authentication's existing writes are outside this business read boundary.

## Deterministic verification fixture

Temporary business database from frozen schema 1.10.0; separate temporary API security database from 1.0.0.
Four real released-role users, Arabic/literal-wildcard profiles, fractional stock, inactive/credit parties,
POSTED/DRAFT/CANCELLED sales, invoice discount, historical costs different from current purchase price,
returns including an older sale, expenses, all-method cash book, ledger and stock history are seeded.

For 2026-10-01..07 independently expected amounts are gross 20.000, returns 5.625, net sales 14.375,
2 invoices, average 10.000; historical cost 8.000, returned cost 2.500, gross profit after returns 8.875,
expenses 4.125, net profit 4.750. Inventory: active 3, low 2, out 1, value 41.248. Cash: opening 50.000,
IN 20.000, OUT 6.125, closing 63.875. Positive receivables 28.875 and payables 11.000 retain inactive parties.
Statements verify brought-forward and page-1 running balances independently, not by calling production
methods for expected values. The slow-stock tie test also checks unchanged core eligibility.

Each SQL HTTP test checks business-table/schema fingerprints before/after; authentication Users/Audit_Log
and API sessions are intentionally excluded because released login/session operations write them. One test
adds/removes a temporary tied product as a fixture. Real TCP HTTP calls use an embedded random-port server.
The integration suite verifies cleanup through master after dropping its databases. The external verification
helper uses a fresh non-sa login and drops only catalogs it owns with allowed disposable prefixes, then checks
zero disposable databases/logins. It only queries master for real AlMahwarDB identity; it never opens or writes
that catalog. No production restoration, migration, fixture insertion or index creation is performed.

## Verification results and required final checklist

The initial SQL run found a teardown check connecting to its already-dropped test database; that new check
was corrected to use master. A subsequent run exposed two new-test setup mistakes (restubbing a throwing
mock and omitting a required fixture category); both were fixed. No inherited assertion was weakened.

Commands use Java 17 and Maven 3.9.9 offline, with the already-installed official core classifier:

```powershell
mvn -B -o test
mvn -B -o -f api/pom.xml verify -Dalmahwar.it=true
```

The SQL command requires ALMAHWAR_IT_DB_* credentials for a disposable development SQL instance; no
credential is saved in tracked code. A temporary ignored PowerShell helper generated the login/password,
ran Maven, checked logs for those actual credentials and cleaned catalogs/logins in finally. That helper is
removed after verification. Reproduction uses the existing TemporaryDatabase/TemporaryApiDatabase fixtures.

| Check | Tests executed | Failures/errors/skips |
|---|---:|---|
| Phase 3 ManagerApiTest | 157 | 0 / 0 / 0 |
| Phase 3 ManagerRulesTest | 7 | 0 / 0 / 0 |
| Phase 3 ManagerSqlServerIntegrationTest | 32 | 0 / 0 / 0 |
| Inherited API non-DB | 105 | 0 / 0 / 0 |
| Inherited API SQL | 45 | 0 / 0 / 0 |
| Complete API | 346 | 0 / 0 / 0 |
| Desktop/core no-DB | 168 | 0 / 0 / 205 intentionally skipped DB tests; 373 discovered |

The Phase 3 role matrix has **76 endpoint/role cases** (19 routes x 4 released roles), plus 19 unauthenticated,
19 must-change and 19 POST-denial cases; direct-service tests cover all four roles and forged extra grants.
Further tests cover disabled/revoked/live-role changes, cost/profit/balance redaction, 400 limits/injection,
safe 503/500, exact strings, presets, zero filling, 404, literal wildcard/Arabic/English/number/quote searches,
real revoked SQL sessions, stock ties, read fingerprints, estimated plans and real HTTP/OpenAPI.

Inherited SQL includes **33 Phase 2 session tests**, 2 API schema-initialization tests and 10 Phase 1/core SQL
tests. The session suite includes its seven concurrency cases. Refresh rotation, reuse detection, logout,
password change, must-change, pwv, credential fingerprint, lockout and readiness retain inherited behavior.
Products POC HTTP 10, product core authorization 2, product sort 4, core adapters 9, core runtime 2,
permission parity 2 and API architecture 4 all pass as part of the 150 inherited API tests. Desktop architecture,
core boundary, connection seam, permissions, money and business-rule tests pass in the 168 no-DB tests.

Final build/packaging and audit evidence are summarized in `target/phase3-verification-summary.json`.
Ignored evidence paths:

* `api/target/phase3-full-verify.log` and Surefire XML: complete API command/results.
* `target/phase3-desktop-regression.log` and root Surefire XML: Desktop command/results.
* `api/target/phase3-query-plans.xml`: 3 SQL plans; no MissingIndexes recommendations in these fixtures.
* `target/phase3-sql-cleanup.txt`: 0 disposable catalogs and 0 temporary logins.
* `target/phase3-business-before.txt`, `target/phase3-business-after.txt`: unchanged real catalog ID from master.

### Required 62-point final audit

1. **Status:** Phase 3 implemented and verified, ready for review; all changes uncommitted.
2. **Branch:** `api-phase3-manager-read`, created from api-phase2-auth.
3. **Starting/current HEAD:** `32c673406025c6c78f9adae2c61e604c954c1604`.
4. **Protected refs:** main/origin/main and peeled v1.0.1 remain `c0234e49e2bf00e094f2bf5de68bebd7fe5f963b`;
   Phase 2 local/remote remain the starting SHA; adoption local/remote remain
   `0aa9f7b9239f6c9a060ba00c8f0309993a9deb99`; api-development remains
   `5cd2819eb2495123c1a41b5db324c267f389d5ee`. v1.0.0 tag object remains
   `7202640ad2f44ef879920cff2d2b08ab13fb6446`, peeled `c27b2e36c2f05596c86cc07d13f59b82122c3945`;
   v1.0.1 object remains `8b97ebb60e333522f784e63935cb2ad2b088c627`. No remote Phase 3 branch exists.
5. **Desktop integrity:** root pom.xml, src, database, config and .github exactly match main; release files/tags unchanged.
6. **AlMahwarDB:** released required schema 1.10.0 remains unchanged; tests never open it or run DDL/DML against it.
7. **AlMahwarApiDB:** schema 1.0.0 remains unchanged; tests create only unique disposable API catalogs.
8. **Discovery:** completed before endpoints; see authority map above, including dashboard/report distinctions.
9. **Metric definitions:** released core rules, no guessed accounting; see financial definitions above.
10. **Implemented:** all 19 GET paths in the endpoint table.
11. **Deferred:** recent activity, physical-cash-only total, mixed-unit sum, bottom-seller ranking, ratios/tax allocation,
    reference/barcode enhancements; reasons above. No required reliable read capability was blocked.
12. **Dashboard:** 9 possible metric names filtered by current released permissions; monthly labels are explicit.
13. **Sales:** POSTED header totals, independent return dates, gross/count average, inclusive bounded dates.
14. **Profit:** historical cost, reversal of returned historical contribution, recorded expense subtraction.
15. **Returns:** recorded return_date, including older sales; no invented status filter; return-only periods supported.
16. **Expenses:** recorded amounts/date and all 9 existing categories.
17. **Cashbox:** all-method cash book, brought-forward plus IN minus OUT.
18. **Inventory:** active count, out <= 0, optional current rounded valuation; no mixed-unit sum.
19. **Low stock:** active quantity <= stored minimum, including zero minimum/zero stock.
20. **Top/low products:** released net-quantity best sellers; released slow-moving eligibility with stable page.
21. **Customer balances:** debit-credit; positive is owed to store, ledger debt list vs cached profile/dashboard.
22. **Supplier balances:** credit-debit; positive is owed by store, same ledger/cache distinction.
23. **Movement decision:** implemented from authoritative Stock_Movements; signed quantity and before/after.
24. **Endpoint permissions:** released permission conjunctions and role results in the tables above; endpoint plus service.
25. **Field restrictions:** PRODUCT_COST, REPORTS_PROFIT and corresponding BALANCE_VIEW; omitted unauthorized fields.
26. **Pagination:** Phase 1 defaults 0/20, size max 100, page max 10000; bounded TOP/trend/categories.
27. **Sort:** strict field/direction allowlists; fixed chronological statements/movements; no client SQL expressions.
28. **Search:** shared literal LIKE helper, Unicode prepared values, max 100 characters.
29. **Date limits:** 366 inclusive days normally; weekly/monthly trends 1096; SQL-local business clock.
30. **KWD:** BigDecimal -> core MoneyUtil -> 3-decimal JSON string.
31. **Quantity:** core QuantityUtil -> 3-decimal JSON string; fractional and negative movement precision retained.
32. **Performance:** aggregate SQL and bounded rows, no Java table loading/N+1 repository loop; 3 captured plans.
33. **Indexes:** none applied; no demonstrated missing-index requirement; conditional measurement candidates above.
34. **Consistency:** released isolation, separate reads can observe concurrent activity; no snapshot claim.
35. **HTTP errors:** existing safe shape; 400/401/403/404/405/503 and safe 500 for other SQL failures.
36. **OpenAPI:** all 19 dev-only GET contracts verified over real HTTP, bearer security and string money schemas.
37. **Phase 3 unit/HTTP:** 164 passed (157 HTTP + 7 direct rules).
38. **Phase 3 SQL:** 32 passed, including real HTTP, expected accounting and query plans.
39. **Permissions:** 76 role/route cases plus direct 4-role tests, field checks and authentication restrictions.
40. **Inherited API:** 150 passed (105 non-DB + 45 SQL); complete API 346 passed.
41. **Phase 2:** 33 SQL session and inherited auth/session unit/HTTP checks passed; security sources unchanged.
42. **Products POC:** inherited 10 HTTP + 2 core authorization + 4 sort checks and SQL coverage passed.
43. **Shared Core:** official classifier/runtime, adapter/parity and architecture tests passed; core unchanged.
44. **Desktop:** 168 executed passed, 205 DB tests intentionally skipped; 373 discovered, zero failures/errors.
45. **Security scan:** 19 GET, zero mutation mappings/SQL, SELECT *, NOLOCK or float/double in Manager production source.
46. **Secret scan:** zero raw JWTs, private keys or credential-bearing JDBC URLs in changed files; actual generated
    verification credentials absent from the final log. Test-only synthetic password is explicit, never a deployed credential.
47. **SQL injection:** sort payloads rejected; search SQL payload bound literally; %, _, [, Arabic, English, numbers and quotes verified.
48. **Business DB safety:** no live catalog connection; master catalog ID unchanged, only owned disposable databases tested.
49. **Cleanup:** zero disposable catalogs and zero generated Phase 2/3 test logins after verification.
50. **JAR:** packaged executable contains Manager classes and official 1.0.1 core; JavaFX/JPA/Hibernate ORM absent.
    Existing Hibernate Validator remains for Bean Validation; it is not an ORM dependency.
51. **Dependencies:** no additions/version changes; api/pom.xml unchanged.
52. **Changed files:** complete 16-file list below; unrelated docker remains untouched.
53. **Git status:** one modified architecture document, 15 new source/test/report files, all unstaged, plus pre-existing untracked docker.
54. **No commit:** HEAD and index unchanged; no git commit performed.
55. **No push:** no git push performed; remote refs unchanged, no remote Phase 3 branch.
56. **No tag:** no git tag performed; tag objects and peeled commits unchanged.
57. **main:** local and remote main remain released v1.0.1.
58. **Phase 2 branch:** local and remote remain starting SHA; Phase 3 work is in its own branch.
59. **No business writes:** GET-only endpoints/read repositories; inherited authentication side effects retained.
60. **Flutter:** no Dart, pubspec or mobile project/config created; Phase 4 not started.
61. **SQL 1205:** existing MAX(...UPDLOCK,HOLDLOCK) document-numbering deadlock remains documented and unfixed;
    Manager reads do not create documents.
62. **Limitations/recommendations:** measure realistic SQL workload; preserve SQL Kuwait clock; no cross-statement snapshot;
    top-product header-tax/discount-rounding differences and cached/ledger balance distinction remain explicit.
    Review and accept this working tree before a separately authorized commit, push or next phase.

### Complete changed-file summary

All production/test paths below are under `api/src/`:

| File | Purpose |
|---|---|
| main/java/com/almahwar/api/manager/ManagerAccess.java | Released permission gates, reused by controller and service |
| main/java/com/almahwar/api/manager/ManagerAnalyticsRepository.java | Core analytics delegation, trend aggregate and stable slow page |
| main/java/com/almahwar/api/manager/ManagerCacheAdvice.java | No-store response policy |
| main/java/com/almahwar/api/manager/ManagerCatalogRepository.java | Core product detail, active inventory and low-stock projections |
| main/java/com/almahwar/api/manager/ManagerController.java | 19 GET routes, method authorization and OpenAPI |
| main/java/com/almahwar/api/manager/ManagerDateRange.java | Central date presets and bounded inclusive range validation |
| main/java/com/almahwar/api/manager/ManagerErrorAdvice.java | Scoped safe connection-failure mapping for core-wrapped SQL |
| main/java/com/almahwar/api/manager/ManagerPage.java | Existing pagination convention, bounded literal search and allowlisted sort |
| main/java/com/almahwar/api/manager/ManagerPartyRepository.java | Core party detail, paged profiles/debts and ledger statement projection |
| main/java/com/almahwar/api/manager/ManagerQueryService.java | Direct-service authorization, DTO construction, bounds and field visibility |
| main/java/com/almahwar/api/manager/ManagerResponses.java | Minimal business DTOs and shared exact decimal formatting |
| test/java/com/almahwar/api/ManagerApiTest.java | 157 HTTP/security/input/precision checks |
| test/java/com/almahwar/api/ManagerSqlServerIntegrationTest.java | 32 disposable SQL/real HTTP/accounting/read-integrity/plan checks |
| test/java/com/almahwar/api/manager/ManagerRulesTest.java | 7 core permission/date/direct-service checks |

Documentation: modified `docs/API_ARCHITECTURE.md`; new `docs/API_PHASE3_MANAGER_READ_REPORT.md`.
No existing Java/test file, Maven definition, SQL script, Desktop resource/config/release file or security
implementation was changed. The unrelated untracked root `docker` retains SHA-256
`E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`.

API PHASE 3 MANAGER READ APIS READY FOR REVIEW

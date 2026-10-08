# Al Mahwar Manager API contract v1 — proposed freeze

Review candidate on `api-phase5-manager-final`, based on Shared Core/Desktop 1.0.1, business schema 1.10.0 and API security schema 1.0.0. Base prefix **`/api/v1`**. This is a management/monitoring application, with permission-protected account administration. It supplies no mobile POS or business-document mutation routes. Flutter communicates only over HTTPS with the API and must never connect to SQL Server. No Flutter implementation or deployment is part of this phase.

Additive compatible fields/routes are preferred. Breaking paths, response types, field meanings or pagination changes require versioning and review. Optional fields and additional enum values must be handled explicitly by clients; never infer permission from a missing financial value or a role name. Re-read `/auth/me` after role/password/session changes. Server authorization always wins. There is no `/api/v2`.

## Common transport rules

Authenticated requests use `Authorization: Bearer <accessToken>` over HTTPS. Login/refresh return credentials only in their dedicated responses; store them securely and never log them. JWTs carry no role or permission authorization state. Every request loads live user/password/session state. Expired/invalid tokens fail 401; revoked, disabled and reset credentials fail 401; restricted must-change sessions fail 403 on Manager reads. Logout, password change, administrative reset/disable and refresh replay invalidation retain Phase 2/4 semantics.

`ApiError` contains `status`, `code`, safe `message`, `timestamp`, `path`, `requestId` and optional `fieldErrors`. Use `code`, not translated message text, for client behavior. Errors include 400 validation, 401 authentication/session, 403 permission/password-change-required, 404 missing object, 405 unsupported method, 415 unsupported media, 429 temporary login lock/rate limit, safe 500 internal failure and 503 unavailable dependencies. Core administration conflicts retain their accepted safe validation mapping. Neither SQL/exception text nor credentials are returned. Send a valid `X-Request-ID` if correlation is needed; the response supplies a validated/generated ID. Manager responses use `Cache-Control: no-store`; permission-filtered responses must not be shared between users.

Money (KWD) and decimal quantities are **JSON strings with exactly three decimal places**, e.g. `"0.000"`, `"-2.500"`, `"12.125"`. Use decimal arithmetic in Flutter, never binary floating-point for accounting. IDs/counts/page metadata remain JSON integers; flags are booleans. Protected cost/profit/balance fields and daily sections are omitted, never replaced with fake zeros or null placeholders. A legitimate nullable contact/expiry is not a financial redaction.

Business dates are `yyyy-MM-dd`. Business timestamps from SQL `DATETIME2` are ISO local timestamps such as `2026-11-01T00:00:00`, with no timezone offset. This preserves Desktop's SQL-server accounting clock: deployments must align that clock to Kuwait (`Asia/Kuwait`, UTC+03:00); API code does not silently reinterpret it from the host/client timezone. Token/session expiry timestamps are UTC instants with `Z`. Inclusive `from`/`to` become `[from 00:00, to+1 day 00:00)`. Default `period=today`, also `this_week` (Sunday) and `this_month`; explicit from/to require both, with period omitted or `custom`. Maximum 366 inclusive days, except weekly/monthly trend up to 1096. Supported dates 1900-01-01 through 9998-12-31. No prior Phase 3 semantics change.

Paged lists use `{items,page,size,totalItems,totalPages}`. `page` is zero based (0..10000); `size` defaults to 20, range 1..100. Empty lists have zero totalPages. Pages beyond the end are empty. Strict sort allowlists accept `field[,asc|desc]` and use a unique ID tie-breaker; default direction is ascending unless documented. `q` is literal, maximum 100 characters, trimmed; `%`, `_`, `[`, quotes, semicolons and comment-like text do not become SQL syntax/wildcards. Details expose independently paged nested collections. Count/page/detail statements use released isolation and may observe concurrent changes; no cross-statement snapshot guarantee. Existing sessions and metadata return arrays without new page wrappers; the inherited own-session history is unpaginated, so production retention/history growth requires operational monitoring or a separately reviewed compatible extension. Every new Phase 5 document collection is bounded.

## Complete endpoint inventory

Paths below include `/api/v1`. “Page” means the common convention above; “range” means the common inclusive date inputs. All protected routes also require a valid unrestricted session, except the explicitly permitted own-account recovery routes. Permissions below are released Shared Core permissions, checked at controller/service boundaries. Phase 1's product access remains unchanged.

| Method and path | Permission / purpose | Major inputs | Response / paging / sensitive rules |
|---|---|---|---|
| POST `/api/v1/auth/login` | Public login; shared lock/hash rules | username, password, optional deviceLabel | LoginResponse: access/refresh expiry, sid, user; restricted login has no refresh; no-store |
| GET `/api/v1/auth/me` | Valid session, including restricted | None | CurrentUserResponse: identity, live role, permission codes; no credential internals |
| POST `/api/v1/auth/refresh` | Valid opaque refresh credential | refreshToken body | LoginResponse; one-time rotation/replay protection; no-store |
| POST `/api/v1/auth/logout` | Own session, including restricted | None | 204; revokes current session |
| POST `/api/v1/auth/logout-all` | Own unrestricted session | None | 204; revokes all own sessions |
| GET `/api/v1/auth/sessions` | Own unrestricted session | None | SessionView array; own device metadata only, no token/hash/fingerprint |
| DELETE `/api/v1/auth/sessions/{sid}` | Own unrestricted session | Session UUID | 204; ownership enforced |
| POST `/api/v1/auth/change-password` | Own session, including restricted | currentPassword, newPassword, confirmPassword | 204; released password policy; invalidates devices, fresh login |
| GET `/api/v1/products` | PRODUCTS_VIEW or PRODUCTS; product lookup | Page, q, includeInactive; sort name/code/salePrice/quantity | Page<ProductResponse>; purchasePrice only PRODUCT_COST, inactive only PRODUCTS |
| GET `/api/v1/manager/dashboard` | DASHBOARD; management home | None | Dashboard(businessDate, metrics); each released metric gated, debt needs balance permissions, profit REPORTS_PROFIT; monthly metrics remain monthly |
| GET `/api/v1/manager/sales/summary` | REPORTS_VIEW + REPORTS_SALES | range | Sales; gross/returns/net/count/average, optional profit with REPORTS_PROFIT |
| GET `/api/v1/manager/sales/trend` | REPORTS_VIEW + REPORTS_SALES | range, grouping=daily/weekly/monthly | Trend, bounded zero-filled buckets; Sunday week; no cost fields |
| GET `/api/v1/manager/sales/top-products` | REPORTS_VIEW + REPORTS_SALES | range, limit 1..100 default 20 | TopProducts; net quantity/revenue, profit only REPORTS_PROFIT |
| GET `/api/v1/manager/sales/slow-products` | INVENTORY + REPORTS_VIEW + REPORTS_INVENTORY | Page, q, days 1..3650 default 30; fixed lastSale,asc | Page<SlowProduct>; current value only PRODUCT_COST |
| GET `/api/v1/manager/invoices` | SALES_VIEW; invoice list | range, Page, q, positive customerId, status, paymentMethod; sort date/number/total | Page<Invoice>; cost SALES_COST_VIEW, profit SALES_PROFIT_VIEW; returns through range.to |
| GET `/api/v1/manager/invoices/{id}` | SALES_VIEW; invoice detail | positive id, page/size for items, returnsPage/returnsSize for returns; no q/sort | InvoiceDetail(invoice, items Page, returns Page); all recorded returns; cost/profit protected |
| GET `/api/v1/manager/inventory/summary` | INVENTORY + REPORTS_VIEW + REPORTS_INVENTORY | None | Inventory current active counts; value only PRODUCT_COST; mixed units not summed |
| GET `/api/v1/manager/inventory/low-stock` | Same inventory permissions | Page, q; sort name/code/quantity(default)/minimumStock | Page<StockProduct>; unit/cost visibility as released |
| GET `/api/v1/manager/products/{id}` | PRODUCTS_VIEW or PRODUCTS | positive id | ProductDetail; inactive only PRODUCTS, cost PRODUCT_COST |
| GET `/api/v1/manager/products/{id}/movements` | Inventory permissions | positive id, range, Page; fixed date,desc; no q | Page<Movement>; signed qty/before/after, historical unit cost PRODUCT_COST |
| GET `/api/v1/manager/customers` | CUSTOMERS_VIEW | Page, q; sort name/code/balance | PartyList(page, optional outstanding); balance fields/sort CUSTOMER_BALANCE_VIEW |
| GET `/api/v1/manager/customers/receivables` | CUSTOMERS_VIEW + CUSTOMER_BALANCE_VIEW | Page, q; sort name/code/balance(default desc) | PartyList; positive ledger debt including inactive customers; totalOutstanding all matches |
| GET `/api/v1/manager/customers/{id}` | CUSTOMERS_VIEW | positive id | Party; cached current balance only CUSTOMER_BALANCE_VIEW |
| GET `/api/v1/manager/customers/{id}/account` | CUSTOMERS_VIEW + CUSTOMER_BALANCE_VIEW | id, range, Page; fixed date,asc; no q | Account with page, opening/running/closing; customer debit-minus-credit |
| GET `/api/v1/manager/suppliers` | SUPPLIERS_VIEW | Page, q; sort name/code/balance | PartyList; balance fields/sort SUPPLIER_BALANCE_VIEW |
| GET `/api/v1/manager/suppliers/payables` | SUPPLIERS_VIEW + SUPPLIER_BALANCE_VIEW | Page, q; sort name/code/balance(default desc) | PartyList; positive ledger debt including inactive suppliers |
| GET `/api/v1/manager/suppliers/{id}` | SUPPLIERS_VIEW | positive id | Party; cached balance only SUPPLIER_BALANCE_VIEW |
| GET `/api/v1/manager/suppliers/{id}/account` | SUPPLIERS_VIEW + SUPPLIER_BALANCE_VIEW | id, range, Page; fixed date,asc; no q | Account; supplier credit-minus-debit, positive means owed by store |
| GET `/api/v1/manager/expenses/summary` | REPORTS_VIEW + REPORTS_EXPENSES | range | Expenses; total/count and released categories |
| GET `/api/v1/manager/cashbox/summary` | CASH + REPORTS_VIEW + REPORTS_CASHBOX | range | Cashbox opening/in/out/net/closing; all payment methods |
| GET `/api/v1/manager/quotations` | QUOTATIONS_VIEW; tracking | range, Page, q, positive customerId, status, pastValidity; sort date/number/total/validUntil | Page<Quotation>; stored status + validity, linkedSale additionally SALES_VIEW |
| GET `/api/v1/manager/quotations/{id}` | QUOTATIONS_VIEW; detail | positive id, page/size for items; no q/sort | QuotationDetail(quotation, items Page), fixed item ID order; no costs, notes or invented taxes |
| GET `/api/v1/manager/daily-summary` | DASHBOARD; compact home | optional date default SQL today | Daily; sales/expenses/cashbox independently permission gated; current inventory separately dated |
| GET `/api/v1/manager/audit` | AUDIT_LOG | range, Page, userId/action/category; fixed date,desc; no q | Page<Audit>; safe event/actor/category only; no descriptions, raw JSON, credential or machine metadata |
| GET `/api/v1/manager/admin/users` | USERS_VIEW | Page, q, role, active; sort name/username/created | Page<AdminDtos.User>; no hashes/versions/fingerprints/lock internals |
| GET `/api/v1/manager/admin/users/{id}` | USERS_VIEW | positive id | AdminDtos.User |
| POST `/api/v1/manager/admin/users` | USERS_CREATE | Create(username, fullName, phone, email, roleCode, password, confirmPassword) | 201 AdminDtos.User; active + must-change, supplied password never returned |
| PUT `/api/v1/manager/admin/users/{id}` | USERS_EDIT | Edit(fullName, phone, email, roleCode, explicit active) | AdminDtos.User; full replacement, username immutable; fresh login required |
| POST `/api/v1/manager/admin/users/{id}/disable` | USERS_EDIT | id | AdminDtos.User; old access/refresh rejected, self/last-admin protection |
| POST `/api/v1/manager/admin/users/{id}/enable` | USERS_EDIT | id | AdminDtos.User; old sessions never resurrected |
| POST `/api/v1/manager/admin/users/{id}/reset-password` | USERS_RESET_PASSWORD | Reset(password, confirmPassword) | AdminDtos.User; must-change, old credentials invalid, no password returned |
| GET `/api/v1/manager/admin/roles` | USERS_VIEW | None | Role(code,name) array of released assignable roles |
| GET `/api/v1/manager/admin/permissions` | USERS_VIEW | None | Permission(code,description,group,roles) array; server enforcement authoritative |
| GET `/api/v1/health` | Public liveness | None | HealthResponse status UP, no DB details |
| GET `/api/v1/health/ready` | Public readiness | None | READY / NOT_READY with safe 200/503; both required databases/schema compatibility |

Inventory counts are operations (METHOD + PATH), not unique paths: expected **45 API operations = 34 GET + 11 mutations**, **34 Manager operations = 29 GET + 5 identity mutations**. Phase 3 business reads remain 19; Phase 5 adds exactly 5 GETs. All 24 business Manager reads plus audit are GET-only. The only Manager writes administer users; no permanent user delete exists. The accepted authentication/session mutations remain separate. The executable SQL/packaged-HTTP test exports the exact OpenAPI inventory under ignored target evidence.

## Invoice and return DTO semantics

Invoice fields: id, number, local date, customer(id/code/name/phone), creator(id/name), status, priceType, paymentMethod, paymentStatus, subtotal, discount, tax, total, paid, remaining, returnedAmount, refundedAmount, netAmount; optional returnCutoffDate/historicalCost/grossProfit. Status is DRAFT/POSTED/CANCELLED. Payment methods: CASH, KNET, CREDIT_CARD, BANK_TRANSFER, CHEQUE, CREDIT, MIXED. Payment status: PAID/UNPAID/PARTIAL, from stored total/paid. Header tax is stored SQL tax: the released Sale Java model omits that property. Do not recalculate a tax-bearing invoice through the model. No line tax/rate allocation exists and none is fabricated.

InvoiceLine fields: id, productId/code, name, unit, quantity, unitPrice, whole-line discount, stored total, all-time returnedQuantity and optional historicalUnitCost. Historical header cost and stored grossProfit follow separate SALES_COST_VIEW / SALES_PROFIT_VIEW permissions, distinct from product/report permissions. grossProfit is the original POSTED invoice gross profit before returns, not a claim about present net profit. It is omitted for DRAFT/CANCELLED invoices even for an authorized user, matching released SaleDao.map. Product names/units are current joined labels; costs/prices/quantities are historical document values.

Return headers contain id, number, date, total, refund and refundMethod. Header aggregates cover every qualifying return, regardless of the nested return page. Return **value** is not cash **refund**: core applies value against debt before cash refunds. Core stores already allocated net return unit prices (invoice/line discounts, rounded down to three decimals) and historical original costs. API never reallocates them. netAmount follows released sales-row total-minus-returned-value. List returns are through the inclusive date-range end, as in ReportDao.salesRows; detail includes all recorded later returns. paid/remaining remain the stored original invoice snapshot and must not be labeled post-return customer debt. Existing customer account routes supply authoritative balances. A full returned invoice can have netAmount `"0.000"`; daily return-only activity can be negative.

## Quotation tracking DTO semantics

Quotation fields: id, number, local date, optional validUntil, businessDate, pastValidity, stored status, registered customer object (nullable id for prospect-only), separately labeled prospectName/prospectPhone, creator, priceType, subtotal/discount/total, optional sentAt/decidedAt, optional linkedSale(id,number,status). QuotationLine: id, productId/code, name, unit, quantity, unitPrice, whole-line discount, stored total. There is no stored quotation tax column. Free-form notes/terms/status notes and internal request IDs are intentionally excluded.

Exactly six statuses exist: **DRAFT, SENT, ACCEPTED, REJECTED, EXPIRED, CONVERTED**. DRAFT may move to SENT/ACCEPTED/EXPIRED; SENT to ACCEPTED/REJECTED/DRAFT/EXPIRED; ACCEPTED to CONVERTED/EXPIRED. REJECTED/EXPIRED/CONVERTED are final. Conversion creates/links a sale draft while the quotation can remain ACCEPTED; sale posting marks CONVERTED. A stored link may also refer to a cancelled sale. Show its actual status; never infer posting/conversion solely from link existence. Access to linked sale metadata additionally requires SALES_VIEW.

`pastValidity` is the core pure rule `validUntil != null && validUntil < businessDate`; expiry today has not passed. Null expiry is not past. It is deliberately independent of stored status, including final states. Desktop quotation service reads may write overdue transitions/audit; API GETs never invoke that path. An overdue DRAFT remains DRAFT in the response until an authoritative Desktop lifecycle operation changes it, while pastValidity=true truthfully indicates age. This is not a new ACTIVE/CANCELLED status or an API expiry job.

## Daily summary and permission matrix

Daily(date, optional sales, optional expenses, optional cashbox, optional inventorySnapshot) composes existing Phase 3 methods for one day. Sales are POSTED invoice totals by sale_date minus stored returns by return_date, even when the original sale is earlier; average is gross/invoiceCount (zero when no invoices). Profit uses historical sale cost minus historical returned cost, then recorded daily expenses. Cashbook uses released opening-before-date and period IN/OUT, across payment methods. InventorySnapshot(businessDate, inventory) is **current**, never an as-of inventory assertion for requested past/future dates. No guessed balance snapshot or duplicate accounting formula is added.

| Released role | Invoices / quotations | Invoice cost/profit | Daily sections |
|---|---|---|---|
| ADMIN | Allowed | Both | Sales + profit, expenses, cashbox, current inventory + valuation |
| ACCOUNTANT | Allowed | Both | Sales + profit, expenses, cashbox |
| CASHIER | Allowed | Neither; keys omitted | Sales without profit |
| STOREKEEPER | Denied | None | Current inventory + valuation only, explicitly dated |

All roles require DASHBOARD for daily; each nested section retains its full Phase 3 permission combination. Live role downgrade takes effect on the next request; forged principal authorities cannot exceed the released role matrix. Restricting a principal's permissions also cannot be bypassed with a broader role name. Existing Phase 4 last-admin/concurrency/security guarantees and the possibility of 503 after an identity change commits remain unchanged: re-read state before retrying administration.

## Performance and operational limits

Explicit projections, bound filters, SQL aggregates, bounded pages, strict allowlists and fixed query counts avoid SELECT-star, client-driven SQL, per-row DAO calls, Java full-table aggregation and dirty reads. Invoice business projections use five fixed read statements; quotation detail projections three; lists count plus page. Business-clock lookups where needed and inherited authentication/session operations are separate, fixed work. Existing sale/quotation date/customer/status, sale-item-parent, return-sale and return-item indexes are used; broad searches and deep offset pages need production measurement. Candidate future covering indexes for measured invoice/quotation list filters or correlated return sums require a separate schema review, never an automatic Phase 5 migration.

Readiness/configuration and test evidence are in `API_MANAGER_BACKEND_READINESS.md` and `API_PHASE5_FINAL_MANAGER_REPORT.md`. The freeze is proposed for review, not a production deployment or an authorization to start Flutter.

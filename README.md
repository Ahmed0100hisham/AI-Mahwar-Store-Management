# Al Mahwar Store Management System — نظام إدارة شركة المحور

Desktop system for a Kuwaiti company selling sanitary ware and plumbing supplies.
Java 17 · JavaFX 21 · FXML · CSS · SQL Server (JDBC) · Maven. Arabic RTL UI, currency KWD (3 decimals).

## Requirements
- Build: JDK 17+, Maven 3.9+
- Run: Java 17 (the JAR contains JavaFX 21), or the self-contained application image (no Java) — see
  "Production deployment"
- SQL Server 2017+ with TCP/IP enabled (Docker only for development / tests)

## Setup
1. Create the database: run `database/01_create_database.sql` in SSMS (it never adds users or demo data).
2. Give the program its database login on each PC — **the bundled `application.properties` holds no credentials**.
   Settings are read in this order (highest first):
   1. JVM system properties: `-Ddb.user=... -Ddb.password=...`
   2. environment variables: `ALMAHWAR_` + the key in capitals, dots/dashes as `_`
      (`ALMAHWAR_DB_USER`, `ALMAHWAR_DB_PASSWORD`, `ALMAHWAR_DB_HOST`, …)
   3. `config/application.properties` (git-ignored; start from `config/application.example.properties`) in the
      folder the program starts from or next to the program, e.g.
      ```properties
      db.host=localhost
      db.port=1433
      db.user=almahwar_app
      db.password=<the password>
      ```
   4. the bundled defaults (host, port, database name, timeouts — nothing secret).
   Use a dedicated SQL login with rights on `AlMahwarDB` only (not `sa`) in production. The password is never
   logged or shown (the JDBC URL and the about screen contain no credentials).
3. First start: with an empty `Users` table the login screen asks for the first system administrator (name,
   username, password typed twice). There is no default account and no default password; the step is offered only
   while no user exists, and a second / concurrent setup creates nothing.
4. Demo data (`database/02_demo_data.sql`) is for test databases only and runs only after the first admin exists.

## Run
```bash
mvn javafx:run                 # run from source
mvn package                    # build + unit tests (DB integration tests are skipped)
java -jar target/almahwar-store-management-1.0.1-app.jar
```

## Database
`database/01_create_database.sql` creates `AlMahwarDB` (collation `Arabic_CI_AS`), all 24 tables and seed
data (roles, units, categories, cash customer). It is safe to run more than once.

- Money and quantities: `DECIMAL(18,3)` / `BigDecimal` scale 3, never FLOAT.
- `line_total` and `remaining_amount` are persisted computed columns; never insert or update them.
- Stock changes only through `StockLedger.post(...)` inside a `TransactionManager.inTransaction(...)` block:
  it updates `Products.quantity` and writes the matching `Stock_Movements` row together, and refuses
  negative stock (also enforced by `CK_Products_quantity_non_negative`).
- Customer/supplier balances change only through `AccountLedger.post(...)` inside a transaction: it writes the
  `Account_Ledger` row and updates the cached `balance` together (see "Customers & suppliers").

## Dashboard
All figures come from SQL Server (`DashboardDao`); "today"/"this month" use the database server's date.
Cards, sections and side-menu entries are filtered by permission (`DashboardService`, `NavigationItem`).
Net profit (month) = sales margin (line total − saved cost) − invoice discounts − returns − expenses.
The dashboard refreshes every `app.dashboard.refresh-seconds` (default 120).

**Demo data (test databases only):** `database/02_demo_data.sql` fills an *empty* database with 30 days of
sales, purchases, returns, payments and expenses. It refuses to run if products or sales already exist,
and needs an ADMIN user (create it from the app's first-run screen first).

## Products & inventory
- **المنتجات** (sidebar): search by name / code / barcode, filters (category, brand, unit, status, low stock),
  add / edit / view / activate-deactivate. Tabs for **الأقسام**, **الماركات** and **الوحدات** (add, edit,
  deactivate, search); all lists come from the database.
- A new product's opening quantity is saved as an `OPENING_BALANCE` stock movement in the same transaction
  (quantity before 0, after = opening quantity). Editing a product never changes its quantity.
- **المخزون** (sidebar): stock balances, **تسوية المخزون** (`ADJUSTMENT_IN` / `ADJUSTMENT_OUT`, reason required,
  user and server time recorded) and **سجل حركات المخزون** (filters: product, type, date from/to).
- Low stock = active product with `quantity <= minimum_stock`; the same rule feeds the dashboard card.
- Movement types ready for later phases: `PURCHASE`, `SALE`, `SALE_RETURN`, `PURCHASE_RETURN`.

| Role | Products | Purchase price | Categories/brands/units | Inventory | Adjust stock |
|---|---|---|---|---|---|
| ADMIN | manage | yes | manage | yes | yes |
| STOREKEEPER | manage | yes | manage | yes | yes |
| CASHIER | view active products | no | — | — | no |
| ACCOUNTANT | view | yes | — | — | no (needs `INVENTORY_ADJUST`) |

**Upgrading from v1.0.0:** rerun `database/01_create_database.sql`. It adds `Products.notes`,
`Stock_Movements.quantity_before` (computed), the non-negative stock check, and renames movement type
`OPENING` to `OPENING_BALANCE`.

## Customers & suppliers
- **العملاء** / **الموردون** (sidebar): search by code, name or phone; filters (active/inactive, with balance,
  over credit limit); add / edit / view / activate-deactivate (never deleted). Codes are generated as
  `C-0001` / `S-0001` when left empty and must be unique.
- Phones: Kuwaiti numbers are stored as 8 digits (`+965 9988 7766` → `99887766`), other countries as
  `+<country><number>`. Phones are not unique; the form warns when another customer/supplier uses the number.
- Details page: basic data, current balance, credit limit and available credit, and tabs for invoices,
  payments, returns (empty until those modules exist) and the **account statement** (date range, search,
  debit, credit, running balance).
- **Accounting:** every balance change is one row in `Account_Ledger` (entry types `OPENING_BALANCE`, `SALE`,
  `SALE_RETURN`, `PURCHASE`, `PURCHASE_RETURN`, `PAYMENT`, `ADJUSTMENT`). Customer balance = Σ(debit − credit),
  supplier balance = Σ(credit − debit). `Customers.balance` / `Suppliers.balance` are a cache written only
  in the same transaction as the ledger row; `CustomerService.balanceMismatches()` must always be empty.
- Opening balances are entered once (when the party is added) and saved as an `OPENING_BALANCE` entry.
- Credit: limit 0 = cash only; `CustomerService.checkCredit(customerId, amount)` is ready for POS.

| Role | Customers | Customer balances & statements | Suppliers | Supplier balances & statements |
|---|---|---|---|---|
| ADMIN | view / edit | yes | view / edit | yes |
| CASHIER | view / edit (cash customers) | no | — | — |
| STOREKEEPER | — | — | view | no |
| ACCOUNTANT | view / edit | yes | view / edit | yes |

**Upgrading from v1.1.0:** rerun `database/01_create_database.sql`. It adds `Account_Ledger` and `Suppliers.area`,
and gives every existing balance a ledger history (`OPENING_BALANCE` + one `ADJUSTMENT` "ترحيل رصيد سابق"
for anything recorded before the ledger existed), so balances stay exactly the same.

## Purchases
- **المشتريات** (sidebar): list with filters (dates, supplier, payment status, status), new purchase, details,
  print preview. Numbers are generated as `PUR-000001`; the supplier's own invoice number is optional but
  cannot be entered twice for the same supplier.
- A purchase is a **DRAFT** (no effect) until it is **POSTED**. Posting runs in one transaction: purchase +
  items, stock in (`Stock_Movements` PURCHASE with the line's net unit cost), product cost, supplier ledger
  (PURCHASE for the total, PAYMENT for what was paid), `Cash_Transactions` OUT for the paid amount, audit log.
  Any failure rolls everything back. Posted purchases are never edited or deleted (corrections = returns, later).
- Payment: cash / KNET / bank transfer / cheque (paid in full), credit (paid 0), partial (0 < paid < total,
  with the method of the paid part). `payment_method` stores how the paid money was paid.
- Costing policy: **last purchase cost** — after posting, `Products.purchase_price` = the line's net unit cost
  (unit cost − line discount / quantity). Each invoice line keeps its own historical cost. `CostingPolicy`
  also contains a weighted-average implementation that can be switched on in `AppContext`.
- Duplicate protection: the save buttons are disabled while saving, each form has a request id (unique in
  the database), posting locks the purchase row and only changes a DRAFT.

**Upgrading from v1.2.0:** rerun `database/01_create_database.sql`. Purchases get `request_id`, `posted_at`,
`posted_by` and a computed `payment_status`; status `COMPLETED` becomes `POSTED`, new purchases start as `DRAFT`.

## Point of sale & sales
- **نقطة البيع** (sidebar): keyboard-first cart. A barcode scanner works as a keyboard: scan → Enter → the
  product is added (a repeat scan increases the quantity) and the focus stays in the search field, so items
  can be scanned one after another. Search also finds the code and the Arabic / English name; an unknown
  code only shows a message. Shortcuts (shown on screen): F2 search, F4 customer, F6 discount, F8 payment,
  F10 complete, Delete remove the selected line, Esc close the search (never clears the cart).
  "مسح السلة" asks for confirmation; a cart can be held as a draft and continued later from the sales list.
- The POS starts with the walk-in customer (customer code `CASH`), who must pay in full. A real customer can
  be searched by code, name or phone; credit / partial sales are checked against the credit limit
  (balance + remaining <= limit; limit 0 = cash only). Only `CUSTOMER_CREDIT_OVERRIDE` (admin) may exceed it,
  after confirming on screen; every override is written to the audit log.
- Retail / wholesale: prices follow `sale_price` / `wholesale_price` (retail when no wholesale price is set).
  Changing the type updates list prices but keeps prices changed by hand and says so. Changing a price needs
  `SALES_PRICE_OVERRIDE` (logged with product, old / new price, user, sale and request id); discounts need
  `SALES_DISCOUNT`. Line total = qty × price − line discount; total = Σ lines − invoice discount.
- **Posting** runs in one transaction: lock the sale (DRAFT only), lock the customer and the products
  (ascending id), check customer, credit limit, prices, discounts and stock again, fix each line's historical
  `Sale_Items.unit_cost` (the product's current cost), stock out (`Stock_Movements` SALE), customer ledger
  (SALE + total, PAYMENT − paid; nothing for the walk-in customer), `Cash_Transactions` IN for the paid amount,
  cost total and profit, audit log, DRAFT → POSTED. Any failure rolls everything back. Two cashiers selling the
  last unit: one sale posts, the other gets "الكمية المطلوبة غير متوفرة بالمخزون" with available / requested.
- Profit = total (after line and invoice discounts) − Σ qty × historical unit cost; it never changes when
  purchase prices change later. Cost and profit are shown only with `SALES_COST_VIEW` / `SALES_PROFIT_VIEW`.
- **المبيعات** (sidebar): list with filters (dates, customer, cashier, payment status, type, status), details
  and the customer invoice preview (company, number, date/time, cashier, customer, lines, totals, KWD,
  the invoice footer; never cost or profit). The company block, logo and footer come from the settings
  (الإعدادات → بيانات الشركة / إعدادات النظام).
  Printing never touches the saved sale.
- The customer page lists the customer's invoices; the dashboard figures use posted sales only.

**Upgrading from v1.3.0:** rerun `database/01_create_database.sql`. `Sale_Items.purchase_price` is renamed
`unit_cost`; Sales get `request_id`, `posted_at`, `posted_by`, `cost_total`, computed `gross_profit` and
`payment_status`; status `COMPLETED` becomes `POSTED` (cost total filled from the lines), new sales start as
`DRAFT`. Nothing is deleted.

## Payments, expenses & cashbox
- **Customer collections (سند قبض, RCV-000001)** from the customers list ("+ تحصيل من عميل") or a customer's page
  ("تسجيل دفعة"); **supplier payments (سند صرف, PAY-000001)** the same way from the suppliers module. The form lists
  only parties with an outstanding amount, shows the balance after the payment, and accepts cash, KNET, bank
  transfer or cheque, a reference, notes and a date (today or an earlier day, never a future one).
- Saving runs in one transaction: the party row is locked, the amount is checked against what is owed
  (**no overpayment**), the payment is inserted, `Account_Ledger` gets a PAYMENT entry (the only thing that changes
  the balance), `Cash_Transactions` gets the money IN (customer) or OUT (supplier) on the same date, audit log.
  The customer / supplier page has a "المدفوعات" tab and the statement shows SALE / PURCHASE and PAYMENT with the
  running balance. A payment never touches stock.
- **Expenses (EXP-000001)**: category (rent, electricity, water, internet, transport, maintenance, salaries,
  office, other), description, amount, method, reference, notes, date. Saved with its `Cash_Transactions` OUT row;
  never in a customer / supplier account.
- **Cashbox (الخزنة)**: current balance, today's in / out / net, and every movement (sales, purchases, payments,
  expenses, returns, deposits, withdrawals) with filters. The treasury includes every method (cash, KNET,
  transfers, cheques). Manual **deposits** and **withdrawals** need a reason; a withdrawal larger than the balance
  is refused. The balance is always Σ IN − Σ OUT, never stored.
- Duplicate protection: every form has a request id (unique in the database); a double click or a retry returns
  the first record. Concurrent payments of the same party are serialised by the row lock, so a debt of 10 paid
  twice at once ends at 0, never −10; manual withdrawals are serialised by an application lock.
- Permissions: `CUSTOMER_PAYMENTS` (admin, accountant, cashier), `SUPPLIER_PAYMENTS`, `EXPENSES`, `CASH` (view) and
  `CASH_ADJUST` (deposits / withdrawals) for admin and accountant; the storekeeper has none.

**Upgrading from v1.4.0:** rerun `database/01_create_database.sql`. Payments, expenses and cash movements get a
`request_id`; expenses get `category` (filled from the old free-text type) and `notes`; cash movements get
`reference_no` and `notes`. Nothing is deleted.

## Returns
- **المرتجعات** (sidebar) has two tabs, sales returns (SRN-000001) and purchase returns (PRN-000001); a return can also
  be started from a posted sale's or purchase's page ("إنشاء مرتجع"). Pick the original invoice, then the quantity per
  line: the screen shows the original, already returned and remaining quantities, and never accepts more than what
  is left. A reason is required; notes are optional. Returns are posted at once and never edited or deleted.
- **Value**: the net price actually invoiced per unit (line discount and the line's share of the invoice discount
  included, rounded down), never more than what is left of the invoice total.
- **Settlement (conservative)**: the value first reduces what the customer owes us / what we owe the supplier
  (ledger SALE_RETURN / PURCHASE_RETURN); only the part beyond that balance is money back (sale: paid to the customer,
  cash OUT; purchase: received from the supplier, cash IN, both with a PAYMENT ledger entry). So nothing is refunded
  that was not paid and no unintended credit balance appears. The walk-in customer has no account: the whole value
  is paid back. No cash movement is written when no money moves.
- **Stock**: SALE_RETURN puts goods back at the sale line's historical cost; PURCHASE_RETURN takes them out and is
  refused if the current stock is not enough. Products are locked in product_id order like every stock document.
- **Profit**: the dashboard's gross profit subtracts each return's value minus its historical cost (`cost_total`).
- Permissions: `SALE_RETURNS` (admin, cashier, accountant), `PURCHASE_RETURNS` (admin, accountant; no longer the
  storekeeper alone).

**Upgrading from v1.5.0:** rerun `database/01_create_database.sql`. Returns get `reason_code`, `notes` and a unique
`request_id`; sales returns get `cost_total` and `Sale_Return_Items.unit_cost` (filled from the original sale lines).
Nothing is deleted.

## Quotations
- **عروض الأسعار** (sidebar; also a "عروض الأسعار" tab on each customer's page) lists quotations (QUO-000001) with
  filters by text (number, customer, phone, prospect), dates, customer, status and price type. A quotation is
  **financially neutral**: saving, sending or accepting it writes no stock movement, ledger entry, cash movement or sale,
  and it is never part of the account statement or the dashboard.
- **Form**: a registered customer, or the walk-in customer with the prospect's name and phone; retail or wholesale
  prices (switching re-prices the lines still at the old list price); products through the usual picker (current stock
  shown for information, nothing reserved); fractional quantities only for units that allow them; Arabic digits
  accepted; a validity date (default 14 days, never before the quotation date), notes and terms. A price other than the
  list price needs `QUOTATIONS_PRICE_OVERRIDE` and is audited; line or quotation discounts need `QUOTATIONS_DISCOUNT`.
  Totals use the same 3-decimal rounding as sales.
- **Workflow**: DRAFT → SENT → ACCEPTED / REJECTED (a draft may be accepted directly; a sent quotation can be reopened as
  a draft). Only drafts are edited or deleted (hard delete; there is no "cancelled" status). Accept / reject take an
  optional note. A passed validity date turns DRAFT / SENT / ACCEPTED into EXPIRED whenever quotations are read or
  changed (no scheduler); an expired quotation cannot be accepted or converted.
- **Convert to sale** (ACCEPTED and still valid, `QUOTATIONS_CONVERT` + `SALES_CREATE`): creates a **sale draft** through
  the normal sales service (customer, products, units and permissions re-checked) and opens it in the point of sale.
  The agreed prices are kept, never replaced silently: if a list price changed, or the stock is now short, the POS shows
  a warning. A draft may be created despite a shortage, but posting is refused until the quantity is fixed. The
  quotation becomes **CONVERTED** only when that sale is posted — in the same transaction, which fails (and rolls the
  sale back) if the quotation expired meanwhile. The agreed price is accepted at posting without
  `SALES_PRICE_OVERRIDE` and is still written to the audit log with the quotation number.
- **One sale per quotation**: converting again returns the existing sale; a unique index (`UX_Sales_quotation`, live
  sales only) stops a second one even under concurrent clicks. Cancelling the sale draft allows a new conversion.
  The sale shows its quotation number and the quotation shows its sale number.
- **Print preview** (A4, same layout as the invoice; the system print dialog can print to PDF): no cost, profit or
  internal ids.
- Permissions: `QUOTATIONS_VIEW / CREATE / EDIT / SEND / ACCEPT / CONVERT / DISCOUNT / PRICE_OVERRIDE`. Admin: all;
  cashier: all but price override; accountant: view and accept / reject; storekeeper: none.
- Audit: `QUOTATION_CREATED / UPDATED / DELETED / SENT / REOPENED / ACCEPTED / REJECTED / EXPIRED /
  CONVERSION_STARTED / CONVERTED / PRICE_OVERRIDE / DISCOUNT`. A `request_id` per form / click makes double submits
  harmless.

**Upgrading from v1.6.0:** rerun `database/01_create_database.sql`. `Quotations` gets `terms`, `status_note`, `sent_at`,
`decided_at`, `decided_by` and a unique `request_id`; `Sales` gets `quotation_id` (with `UX_Sales_quotation`).
Nothing is deleted.

## Reports & analytics
- **التقارير** (sidebar): one tab per group (sales & profit, purchases & expenses, cashbox, inventory, customers &
  suppliers, quotations, user activity). Each report has filters, Apply / Reset, summary cards, a paged table,
  print preview (A4 landscape, every page; "Save as PDF" from the print dialog) and **Excel export (.xlsx, Apache POI)**
  of every matching row with the current filters. Reports are **read-only**: they never write (an open quotation past
  its validity is *shown* as expired, not updated).
- **Dates**: inclusive days on the server's calendar (Today, Yesterday, This week — starts Sunday —, This month,
  Last month, This year, Custom); applied as `col >= from AND col < to + 1 day`, so 00:00:00 and 23:59:59 are both
  inside. From after To is refused.
- **Sales**: Gross sales = posted invoices by posting date; Returns = sales returns by return date; **Net sales =
  Gross − Returns** (a September sale returned in October: September gross, October returns). Invoice count and
  average invoice; detail rows show each invoice's returns up to the end of the period.
- **Profit** (historical cost only — the cost stored on each sale line, never today's purchase price): Sales revenue
  − COGS = gross profit before returns; Return revenue − returned COGS = return profit reversal; gross profit after
  returns − expenses = **net profit**. The cashbox balance is never used as profit.
- **Purchases**: gross / returns / net by the same timing rule. **Expenses**: total and per category.
  **Cashbox**: opening (every movement before the period, never assumed 0) + in − out = closing; with direction /
  source / method / search filters all four figures cover the filtered movements.
- **Inventory**: quantity × *current* purchase cost (a current valuation, not historical COGS); **low stock**
  (quantity ≤ minimum, shortage = max(minimum − quantity, 0)); **stock movements** (signed quantity, before / after);
  **best sellers** ranked by net quantity (sold − returned in the period; revenue after the line's share of the
  invoice discount); **slow-moving** products (in stock, not sold for 30 / 60 / 90 days, or never sold).
- **Customers & suppliers** from the account ledger: customer debts (debit − credit > 0), supplier payables
  (credit − debit > 0), last document / payment; statements reuse the account statement (opening, running balance,
  closing). Quotations are never in a statement.
- **Quotations**: counts by effective status, values (never revenue), conversion rate = converted ÷ (all − drafts).
  **User activity**: the audit log (description only; old / new values are not shown).
- **Dashboard**: the sales cards now show **net** sales («صافي مبيعات اليوم / الشهر»), with gross and returns in the
  caption; the figures equal the reports for the same period (tested).
- **Permissions**: `REPORTS_VIEW` plus `REPORTS_SALES / PROFIT / PURCHASES / EXPENSES / CASHBOX / INVENTORY /
  PARTIES / QUOTATIONS / AUDIT`, and `REPORTS_EXPORT` for Excel. Admin: all; accountant: the financial reports and
  export; storekeeper: stock reports (inventory value with `PRODUCT_COST`, never profit); cashier: the sales report
  and best sellers (no cost, no profit, no export). Checked in the service: cost and profit fields are removed from
  the data itself, so neither the screen nor the export can show them.
- No database change: the existing date indexes cover every report filter.

## Settings (company profile & system settings)
- **الإعدادات** (sidebar, `SETTINGS_VIEW`; changes need `SETTINGS_EDIT` — both admin only for now) has three tabs:
  company profile (Arabic / English name, phones, email, address, country, tax and CR numbers, logo), system
  settings (default quotation validity 1–365 days, default quotation terms, invoice footer, report footer) and
  about (program version, database connection, SQL Server version, schema version; never credentials).
- **One central source**: the values live in the database (`System_Settings`), so every PC prints the same company
  block. The sales invoice, purchase, quotation and report prints and the Excel export header read them through
  `SettingsService`; nothing about the company is left in `application.properties`.
- **Currency** is shown but read-only: amounts are KWD with 3 decimals everywhere (not a setting).
- **Logo**: PNG or JPEG, at most 1 MB, 16–4000 px per side, decoded fully before it is accepted. It is stored in the
  database (`Company_Logo`, one row) — no file paths, so it moves with the database and appears on every PC at once.
- **Quotation defaults**: a new quotation is valid until today + the configured days and starts with the default
  terms; changing the defaults never changes quotations already saved (each keeps its own terms).
- **Saving** writes both tabs in one transaction (all or nothing) and records `COMPANY_SETTINGS_UPDATED` /
  `SYSTEM_SETTINGS_UPDATED` (changed fields, old → new) or `LOGO_CHANGED`. Leaving the screen with unsaved changes
  (side menu, logout, closing the window) asks first: save, discard or stay. An inactivity logout discards them.
- **Version**: one source — Maven fills `version.properties` with the project version at build time.

**Upgrading from v1.7.0 (Phase 12):** rerun `database/01_create_database.sql`. It creates `System_Settings`,
`Company_Logo` and `Schema_Info` (schema 1.8.0) and inserts only the missing default settings — a rerun never
resets saved values, and the schema version never moves back.

## Users & security
- **المستخدمون** (sidebar, admin only — `USERS_VIEW`, `USERS_CREATE`, `USERS_EDIT`, `USERS_RESET_PASSWORD`):
  list with search / role / status filters, create, edit (name, phone, email, role, active), enable / disable,
  unlock, reset password, and the read-only permission matrix (exactly what `RolePermissions` grants).
  Roles are the four existing ones only (ADMIN, ACCOUNTANT, CASHIER, STOREKEEPER).
- **Users are never deleted** (their documents and audit history stay attributable): they are disabled. A disabled
  user cannot log in; documents keep their name.
- **Usernames**: 3–50 English letters, digits, `. _ -`; stored trimmed and lower case; unique (any case). The
  username never changes after creation.
- **Admin safety (enforced in the service, inside a transaction that locks the active admins)**: there is always
  at least one active ADMIN — the last one cannot be disabled or demoted, also when two admins act at once; an
  admin cannot disable themself or change their own role; an admin resets other users' passwords, not their own.
- **Password policy**: at least 8 characters with letters and digits, at most 128, not the username, typed twice.
  Stored only as PBKDF2-HMAC-SHA256 (600,000 iterations, random salt; older hashes are upgraded at login).
- **Change my password** (top bar, every user): current password + new password twice; afterwards the session ends
  and the user logs in again with the new password.
- **Admin reset**: the admin types a temporary password (never shown or logged anywhere) and by default forces a
  change at the next login. Until it is changed the session has **no permissions at all** — the program shows only
  the change-password page, and every service call is refused.
- Role changes and disabling apply from the user's next login (a desktop session already open on another PC keeps
  its permissions until it logs out or times out).
- Audit: `USER_CREATED`, `USER_UPDATED`, `USER_ENABLED`, `USER_DISABLED`, `USER_ROLE_CHANGED`, `PASSWORD_CHANGED`,
  `PASSWORD_RESET`, `ACCOUNT_LOCKED`, `ACCOUNT_UNLOCKED`, `LOGIN`, `LOGIN_FAILED`, `LOGOUT` — never a password or
  hash (old / new values only for name, phone, email and role).

**Upgrading from v1.8.0 (Phase 13A):** rerun `database/01_create_database.sql` — it adds `Users.must_change_password`
(default 0) and `Users.password_changed_at`; existing users and passwords keep working (schema 1.9.0).

## Backup & restore
**النسخ الاحتياطي** (sidebar, admin only — `BACKUP_VIEW`, `BACKUP_CREATE`, `BACKUP_VERIFY`, `BACKUP_RESTORE`, checked
in `BackupRestoreService`, not only by the screen; the accountant, cashier and storekeeper have none of them):
create a full backup, the backup history, verify a backup, restore from a backup.

```
BackupController (backup.fxml) ─► BackupRestoreService (+Impl) + BackupPolicy (rules, unit-tested)
                                   ├─► DatabaseBackupDao  — BACKUP / RESTORE / HEADERONLY / VERIFYONLY, run from master
                                   └─► BackupHistoryDao   — Backup_History + audit entries
                                                            ─► SQL Server
```

### Where backup files live (the SQL Server-side path)
`BACKUP DATABASE` and `RESTORE` are executed **by SQL Server**: the SQL Server service reads and writes the `.bak`
file **on the SQL Server machine**. `backup.server-directory` is therefore a folder **as SQL Server sees it** — e.g.
`D:\SQLBackups` on the server, a UNC share `\\fileserver\backups\almahwar`, or `/var/opt/mssql/backup` for SQL Server
on Linux / Docker. It is not a folder of the PC running the program (unless SQL Server runs on that same PC). The
program never reads or copies the files itself and never takes a path from the screen: the folder comes only from the
configuration, and only file names generated by the program are ever used.

### Configuration (no credentials)
| Key | Default | Meaning |
|---|---|---|
| `backup.enabled` | `true` | `false` disables create / verify / restore (the screen says why) |
| `backup.server-directory` | empty | the backup folder on the SQL Server machine; empty = SQL Server's own default backup folder (`InstanceDefaultBackupPath`, SQL Server 2019+) |
| `backup.file-prefix` | empty | file name prefix; empty = the database name |

Same precedence as every setting: `-Dbackup.server-directory=...`, the environment variable
`ALMAHWAR_BACKUP_SERVER_DIRECTORY`, `config/application.properties` (in a properties file write each backslash twice:
`backup.server-directory=D:\\SQLBackups`), then the bundled default.

### Production SQL Server requirements
- The folder exists on the SQL Server machine and the **SQL Server service account** (e.g. `NT SERVICE\MSSQLSERVER`,
  or the domain account for a UNC share) can write to it. Errors such as "folder missing" or "no permission" are
  shown in Arabic; the technical SQL Server message goes to the application log.
- The program's SQL login needs `db_backupoperator` on the database to **back up**, and `CREATE DATABASE` in
  `master` to **verify** (SQL Server requires it to read any backup file with `RESTORE HEADERONLY / VERIFYONLY`);
  without it the program reports exactly that, and never marks the backup as failed for it. See "Production
  deployment" for the tested least-privilege script.
- **Restoring over the existing database** needs `dbcreator` or `sysadmin` on the server. Give the everyday login only
  what it needs; for the (rare) restore, start the program with an administrative SQL login for that session
  (`-Ddb.user=... -Ddb.password=...` or the `ALMAHWAR_DB_*` environment variables) — or let the DBA restore.
- Works on every edition including Express (no backup compression is requested).
- Copying backups to another disk / site is the server administrator's job (the program writes to one folder).

### Create
1. File name `<prefix>_yyyy-MM-dd_HHmmss.bak` from **SQL Server's clock** (the same on every PC), e.g.
   `AlMahwarDB_2026-10-06_173500.bak`; if that name exists (SQL Server is asked with `RESTORE HEADERONLY`, and the
   history's UNIQUE file name settles a race between PCs) `_2`, `_3`, … is added — **a file is never overwritten**.
2. A history row is reserved as `CREATING`, then `BACKUP DATABASE … WITH COPY_ONLY, CHECKSUM, NOINIT` (copy-only:
   any differential / log backup plan of the server's administrator stays intact).
3. The file must then hold exactly one full backup of this database → `COMPLETED` (with its size); any SQL Server
   error → `FAILED` with a short Arabic note. A backup is never recorded as successful when SQL Server failed.
4. Right after, the backup is **verified** automatically.

Statuses: the operation (`CREATING` → `COMPLETED` | `FAILED`) is separate from the verification (`NOT_VERIFIED`,
`VERIFIED`, `VERIFY_FAILED`, with time and user). Backups are **never deleted automatically** (no retention policy yet).

### Verify
`RESTORE HEADERONLY` (one backup set, a full database backup, of this database) and `RESTORE VERIFYONLY … WITH
CHECKSUM` — SQL Server reads the whole file and checks every page checksum. A damaged, missing or foreign file is
marked `VERIFY_FAILED` with the reason.
**VERIFYONLY is not a full substitute for a real test restore**: it proves SQL Server can read the file, not that the
restored database is what you expect. Restore a backup now and then into a separate test database (SSMS: *Restore
Database* under another name) and open it.

### Restore — the safety flow
1. `BACKUP_RESTORE` permission (service layer), and the **database name typed exactly** (one click is never enough;
   the warning in the page explains what will be lost).
2. The backup must be in the history, `COMPLETED`, a program-named file, of this database, and made with **the same
   schema version** as this program (an older backup: restore it manually, then rerun the schema script; a newer one:
   update the program).
3. One backup / restore at a time **across all PCs** (SQL Server application lock) and on this PC; a second request
   — double click included — is refused at once.
4. `RESTORE_REQUESTED` is audited; the backup is **verified** again; its files must match the database's files.
5. A **safety backup** `PRE_RESTORE_<prefix>_….bak` of the current database is made and verified. **If it fails,
   nothing is restored** (`RESTORE_FAILED`). Otherwise `PRE_RESTORE_BACKUP_CREATED`.
6. From a connection to `master` (never from inside the database): `ALTER DATABASE … SET SINGLE_USER WITH ROLLBACK
   IMMEDIATE` and `RESTORE DATABASE … WITH REPLACE, RECOVERY, CHECKSUM, MOVE …` (onto the current files) in one batch;
   `MULTI_USER` is always switched back. **Other PCs are disconnected**; their open sessions get errors until the
   users log in again.
7. The session that restored **ends** and the program returns to the login screen (the data, users and passwords
   may be different now).
8. **Post-restore validation** (read only): ONLINE, MULTI_USER, a fresh connection works, `Schema_Info` readable and
   equal to the program's version, critical tables exist (Users, Products, Sales, Purchases, Customers, Suppliers,
   Audit_Log, …), an active admin exists; basic consistency checks (stock vs. movements, negative stock, customer /
   supplier balances) are reported as notes — nothing is repaired automatically.
9. If the restore failed and left the database unchanged, the message says so (the session goes on). If it left the
   database unusable, or validation found a critical problem, the **safety backup is restored automatically** (the
   exact state just before) and the message says so.

### Audit
`BACKUP_STARTED`, `BACKUP_COMPLETED`, `BACKUP_FAILED`, `BACKUP_VERIFIED`, `BACKUP_VERIFY_FAILED`, `RESTORE_REQUESTED`,
`PRE_RESTORE_BACKUP_CREATED`, `RESTORE_COMPLETED`, `RESTORE_FAILED` (file names and short Arabic reasons — never a
password, credential or stack trace). A restore replaces `Audit_Log` itself with the backup's older copy, so after it
the program writes `RESTORE_REQUESTED`, `PRE_RESTORE_BACKUP_CREATED` and `RESTORE_COMPLETED` again into the restored
database, and merges the backup history it knew just before (later backups, the safety backup). Limits: other
entries written between the backup and the restore are only in the safety backup; SQL Server's own record of every
restore is in `msdb.dbo.restorehistory`, and every step is in the application log.

### Development with Docker
The test SQL Server runs in the container `almahwar-sql`; its backup folder is inside the container:
```
docker exec -u 0 almahwar-sql mkdir -p /var/opt/mssql/backup
docker exec -u 0 almahwar-sql chown mssql:mssql /var/opt/mssql/backup
```
and `config/application.properties` (git-ignored) contains `backup.server-directory=/var/opt/mssql/backup`. Without
a volume the files disappear with the container — create it with `-v almahwar-backups:/var/opt/mssql/backup` to keep
them. `docker cp` is only a developer convenience; the program never depends on it.
Integration tests: `BackupRestoreIntegrationTest` (`-Ddb.it=true`, folder `-Dbackup.it.directory`, default
`/var/opt/mssql/backup`) only **backs up** `AlMahwarDB`; every restore runs on a temporary database
`AlMahwarRestoreTest_<random>` built from the schema script and dropped afterwards.

### Recovery procedure (if the program could not put the database back)
1. On the SQL Server, as an administrator (SSMS), find the newest `PRE_RESTORE_…` file in the backup folder (or in
   `msdb.dbo.backupset`).
2. Restore it:
   ```sql
   ALTER DATABASE AlMahwarDB SET SINGLE_USER WITH ROLLBACK IMMEDIATE;   -- only if the database is online
   RESTORE DATABASE AlMahwarDB FROM DISK = N'D:\SQLBackups\PRE_RESTORE_AlMahwarDB_2026-10-06_173500.bak'
       WITH REPLACE, RECOVERY, CHECKSUM;
   ALTER DATABASE AlMahwarDB SET MULTI_USER;
   ```
3. Check `SELECT schema_version FROM AlMahwarDB.dbo.Schema_Info;`, start the program and log in.

**Upgrading from v1.9.0 (Phase 13B):** rerun `database/01_create_database.sql` — it adds `Backup_History`
(schema 1.10.0); nothing else changes.

## Production deployment
Docker is only the development / test SQL Server of this project. In production SQL Server is a normal installation
(Windows Server or a PC acting as server); nothing in the program needs Docker.

### Requirements
- **SQL Server 2017+** (2019+ recommended; Express works), TCP/IP enabled, reachable from every PC (default port 1433).
- Each PC: **Windows 10/11**. Either the self-contained application image (no Java needed, recommended) or
  **Java 17** (the JAR contains JavaFX 21 — no separate JavaFX install).

### 1. SQL Server: database and logins (least privilege)
1. Run `database/01_create_database.sql` once as an administrator (SSMS). It creates `AlMahwarDB` and its schema;
   it never creates users of the program or demo data.
2. Create the **application login** used by every PC — never `sa`, never `sysadmin`:
   ```sql
   CREATE LOGIN almahwar_app WITH PASSWORD = N'<strong password>', DEFAULT_DATABASE = AlMahwarDB;
   USE AlMahwarDB;
   CREATE USER almahwar_app FOR LOGIN almahwar_app;
   ALTER ROLE db_datareader     ADD MEMBER almahwar_app;   -- read
   ALTER ROLE db_datawriter     ADD MEMBER almahwar_app;   -- insert / update / delete
   ALTER ROLE db_backupoperator ADD MEMBER almahwar_app;   -- "إنشاء نسخة احتياطية"
   USE master;
   CREATE USER almahwar_app FOR LOGIN almahwar_app;
   GRANT CREATE DATABASE TO almahwar_app;   -- SQL Server requires it to READ any backup file (verify)
   ```
   This set was tested on SQL Server 2022: everything in the program works, backup and verify included; restore is
   refused by SQL Server (Msg 3110). Without the last two lines backups cannot be checked and the program says so.
   The program needs no DDL rights (the schema is changed only by the upgrade script, run by an administrator).
3. **Restore** needs `dbcreator` or `sysadmin`. Keep that for a separate **administrative login** (or the DBA): when
   a restore is really needed, start the program once with it — `ALMAHWAR_DB_USER` / `ALMAHWAR_DB_PASSWORD` set in that
   session only, or `-Ddb.user=... -Ddb.password=...` — or restore in SSMS (see "Recovery procedure"). Its password
   is never stored in the program or its configuration.
4. **TLS**: give SQL Server a certificate trusted by the PCs (issued by the company / a public CA, for the name in
   `db.host`) and keep `db.encrypt=true`, `db.trust-server-certificate=false` (the defaults). If the certificate is
   issued for another name, set `db.host-name-in-certificate`. `db.trust-server-certificate=true` disables the check
   (development servers only; the program logs a warning at every start).
5. Backups: a folder **on the SQL Server machine** (e.g. `D:\SQLBackups\AlMahwar`) writable by the SQL Server
   service account — see "Backup & restore".

### 2. Configuration on each PC
Copy `config/application.example.properties` to `config/application.properties` and fill it in. The program finds
the file — the first that exists — at: the path in `-Dalmahwar.config=...` / `ALMAHWAR_CONFIG`; `config\` in the
folder it is started from; `config\` next to the program (the application image's folder, or the JAR's folder).
Any key can instead be an environment variable `ALMAHWAR_<KEY>` (e.g. `ALMAHWAR_DB_PASSWORD`) or a JVM option
(`-D`), which win over the file. Protect the file with Windows permissions (only the users of the PC).
The program never logs or shows the user name, the password or the full connection settings.

| Key | Required | Notes |
|---|---|---|
| `db.host` | yes | server name / IP, or `SERVER\INSTANCE` (write `\\` in the file) |
| `db.port` | yes | 1–65535 (default 1433) |
| `db.name` | yes | `AlMahwarDB` |
| `db.user`, `db.password` | yes* | *unless `db.integrated-security=true` (Windows authentication) |
| `db.encrypt`, `db.trust-server-certificate`, `db.host-name-in-certificate` | — | TLS, see above |
| `db.login-timeout` | — | seconds to wait for the server (1–60, default 5) |
| `backup.server-directory` | for backups | folder on the SQL Server machine |
| `app.log.directory`, `app.log.max-file-mb`, `app.log.files` | — | technical log, see below |

### 3. Install the program
- **Recommended — application image with its own Java runtime** (no Java on the PC). Build it once:
  ```bat
  mvn clean package
  mkdir dist\in & copy target\almahwar-store-management-1.0.1-app.jar dist\in\
  jpackage --type app-image --name AlMahwar --input dist\in ^
    --main-jar almahwar-store-management-1.0.1-app.jar --main-class com.almahwar.Launcher ^
    --app-version 1.0.1 --vendor "Al Mahwar" --java-options "-Dfile.encoding=UTF-8" ^
    --add-modules java.base,java.desktop,java.sql,java.naming,java.logging,java.management,java.xml,java.security.jgss,java.scripting,java.net.http,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.charsets,jdk.unsupported,jdk.zipfs,jdk.jfr,java.instrument,jdk.management ^
    --dest dist\out
  ```
  Copy `dist\out\AlMahwar` to each PC (e.g. `C:\Program Files\AlMahwar`), put `config\application.properties` inside
  that folder, and create a shortcut to `AlMahwar.exe`. A Windows installer (`--type msi` / `exe`) additionally needs
  the WiX Toolset on the build PC; the application image needs nothing else.
- **Alternative — JAR**: install Java 17 and start `java -jar almahwar-store-management-<version>-app.jar` with
  `config\application.properties` next to the JAR.

### 4. First start
The login screen first runs a **read-only health check**. With an empty `Users` table it then offers the one-time
creation of the first administrator (no default account or password exists; weak passwords are refused).

### Startup health check
Before the login, in this order: settings complete → SQL Server reachable and login accepted (TLS checked) →
database exists and is ONLINE → `Schema_Info` readable and **equal** to the program's version (older: run the upgrade
script; newer: install the newer program — the program refuses to run in both cases) → critical tables exist →
an active administrator exists (if users exist; otherwise only a warning — the program still works). It never repairs,
migrates, creates users or changes data. The login screen shows the reason in Arabic, without user names, passwords,
connection strings or SQL errors; the technical detail is in the log.

### Application log
Technical log (separate from the business audit log `Audit_Log`): `%LOCALAPPDATA%\AlMahwar\logs\almahwar-0.0.log`
(or `app.log.directory`), rotated at `app.log.max-file-mb` (5 MB) with at most `app.log.files` (5) files per program
instance — it never grows without limit. It records start, stop, the database settings without credentials, health
problems, failed operations and unexpected errors with their stack traces; values that look like passwords are
masked. Unexpected errors show the user one safe Arabic message (never a stack trace) and the program goes on.

### Several program windows on one PC
Allowed and harmless: each window is its own login session; they share nothing on the PC except the log folder
(each process writes its own log files); backup / restore are serialized by SQL Server for all PCs anyway.
No single-instance lock is needed.

### Shutdown
Closing the window asks first if a form or the POS cart has unsaved work, and is **refused while a backup, verify or
restore runs** (as are logout and leaving the page); an inactivity logout that falls in such an operation waits for
its end. On exit the session ends and the logout is written to the audit log.

### Upgrade procedure (new program version)
1. Take a backup (النسخ الاحتياطي → إنشاء نسخة احتياطية, verified) — or in SSMS.
2. Close the program on all PCs.
3. As an administrator, run the new `database/01_create_database.sql` against the server: it only adds what is
   missing and moves `Schema_Info` forward; it never drops tables or data and can be run again safely.
4. Check: `SELECT schema_version FROM AlMahwarDB.dbo.Schema_Info;` → the new version (now 1.10.0).
5. Install the new program on every PC and start it: the health check must pass (an old program on an upgraded
   database refuses to run with "قاعدة البيانات مُرقّاة لإصدار أحدث").

### Rollback / recovery
- A restore inside the program always takes a verified safety backup `PRE_RESTORE_…` first and puts it back
  automatically if the restore fails half-way.
- Otherwise restore in SSMS with an administrative login (see "Recovery procedure" under "Backup & restore"), then
  run the matching program version.

### Troubleshooting (login screen messages)
| Message | Check |
|---|---|
| إعدادات الاتصال بقاعدة البيانات غير مكتملة | the listed keys in `config\application.properties` / `ALMAHWAR_*` |
| تعذّر الوصول إلى خادم SQL Server | server running, `db.host` / `db.port`, TCP/IP enabled, firewall |
| رفض خادم SQL Server بيانات الدخول | `db.user` / `db.password`, SQL Server authentication mode |
| تعذّر إنشاء اتصال مشفّر موثوق | the server certificate is not trusted by this PC or not issued for `db.host` |
| قاعدة البيانات … غير موجودة / غير متاحة | `db.name`, database ONLINE, the login has a user in it |
| قاعدة البيانات أقدم من هذا البرنامج | run the upgrade script (after a backup) |
| قاعدة البيانات مُرقّاة لإصدار أحدث | install the newer program version |
| جداول أساسية مفقودة / Schema_Info غير موجود | wrong database, or the schema script was not run |
Details: the application log.

See also `PRODUCTION_CHECKLIST.md`.

## Login & permissions
- Wrong username and wrong password give the same message ("اسم المستخدم أو كلمة المرور غير صحيحة") and take the
  same time.
- Roles → modules are defined in `service/RolePermissions` (ADMIN, CASHIER, STOREKEEPER, ACCOUNTANT).
  Sales: the cashier sells (view, create, post, discount) without cost, profit, price override or credit
  override; the accountant views sales with cost and profit; the storekeeper has no sales permissions.
  The seeded `MANAGER` role has no permissions and cannot log in.
- After `security.login.max-attempts` (5) wrong passwords the account is locked for
  `security.login.lock-seconds` (300 s) — temporary only, it expires by itself. The lock is stored in
  `Users.failed_login_attempts` / `Users.locked_until` (database server time), so it survives restarts and applies
  on every PC. An administrator can lift it at once (المستخدمون → إلغاء الإيقاف المؤقت).
- Upgrading an existing database: just rerun `database/01_create_database.sql`; it adds missing columns.
- Inactive sessions end after `app.session.timeout-minutes` (default 30).
- Logins, failed logins and logouts are written to `Audit_Log`.

## DAO integration tests
Run against a database created by the script (test rows are removed afterwards):
```bash
mvn test -Ddb.it=true -Ddb.host=localhost -Ddb.port=1433 -Ddb.user=sa -Ddb.password=YOUR_PASSWORD
```

## Structure
See [ARCHITECTURE.md](ARCHITECTURE.md) for layers, rules and the REST API / Flutter plan.

```
src/main/java/com/almahwar
  MainApp.java, Launcher.java
  config/      AppConfig, DatabaseConnection, AppContext (wires services)
  controller/  JavaFX controllers (+ support/: Navigator, ViewLoader, AlertUtil, Icons)
  model/       entities (BigDecimal money, scale 3)
  dao/         JDBC data access (PreparedStatement)
  service/     business logic: interfaces + *Impl, SecurityContext, permissions, StockLedger
  util/        MoneyUtil, QuantityUtil, PasswordHasher, PhoneNumbers (no JavaFX)
src/main/resources
  fxml/  css/  images/  application.properties
database/      SQL scripts
```

## REST API for mobile clients (in development)

The `api/` folder holds a separate Spring Boot project — the REST API for the future mobile app. It is not part of
the desktop build: the desktop still builds alone with `mvn package` in this folder, exactly as released (v1.0.0).
Mobile clients only ever reach SQL Server through this API, over HTTPS. Build and test it from `api/`
(`mvn verify`); design, configuration and security: [docs/API_ARCHITECTURE.md](docs/API_ARCHITECTURE.md).

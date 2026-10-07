# Release Notes — Al Mahwar Store Management System 1.0.1

**نظام إدارة شركة المحور** — internal architecture maintenance release of the desktop application.

| | |
|---|---|
| Application version | **1.0.1** (previous: 1.0.0) |
| Database schema | **1.10.0** — unchanged; 1.0.1 runs on exactly the same database as 1.0.0 |
| Java / JavaFX | 17 / 21 |
| Entry point | `com.almahwar.Launcher` (runnable JAR `almahwar-store-management-1.0.1-app.jar`) |
| New artifact | `almahwar-store-management-1.0.1-core.jar` (reusable business core) |

## What changed

**No user-facing change is intended.** Screens, icons, keyboard shortcuts, the point of sale, permissions, login and
lockout, password hashing, accounting, stock, costing, credit, quotations, reports, backup / restore and the database
are exactly as in 1.0.0.

The release prepares the existing business logic to be reused — not rewritten — by a future server (the REST API for
mobile clients), so that sales, purchases, returns and financial rules exist in one place only:

* **Connection seam.** The DAOs and the transaction helper obtain connections through a small `ConnectionProvider`
  interface (`com.almahwar.dao`). The desktop keeps its default — the same `DatabaseConnection` (same URL, same
  settings, a new `DriverManager` connection per call, no pool); transaction behaviour, lock order and SQL text are
  unchanged.
* **Audit-origin seam.** The audit log's `machine_name` comes from a per-DAO supplier; the desktop default is this
  computer's name, resolved exactly as before.
* **Reusable core artifact.** The build also produces `almahwar-store-management-1.0.1-core.jar`: models, rules,
  services and DAOs, without JavaFX, UI resources or configuration files, and without the desktop-only parts (the
  single-user desktop session, backup / restore, the startup health check).

## Verification

* **Golden behaviour tests** (new): 17 scenarios — sales (cash, credit, partial, discounts, price and credit
  override), repeated requests, purchases with costing, sale and purchase returns, customer / supplier payments,
  expenses, stock adjustments, quotation conversion, injected failures (complete rollback), permission refusals — run
  on a temporary database. The complete resulting database state was recorded on 1.0.0 and is **byte-identical** on
  1.0.1, both with the desktop connection provider and with a `javax.sql.DataSource` provider.
* **Concurrency regression** (new): simultaneous sellers of the last units, opposite lock order, concurrent credit
  sales, repeated request ids, cashbox withdrawals — same results on 1.0.0 and 1.0.1.
* **Core boundary tests** (new): the core uses no JavaFX / UI / Spring / API code, reaches no desktop-only class,
  has no hidden global state other than the connection seam, and every business service still takes a
  `SecurityContext`.
* All existing unit and SQL Server integration suites, the end-to-end screen suites, the JAR smoke test and the visual
  check at 1366×768 and 1920×1080.

## Known limitation (unchanged from 1.0.0, documented)

Document numbers are generated with `SELECT MAX(...) WITH (UPDLOCK, HOLDLOCK)`. When many new documents are created
at the same instant — reproduced with 12 simultaneous credit sales to the same customer — SQL Server can end one of
them as a deadlock victim. That transaction is rolled back completely (no partial invoice, stock, ledger or cash
entry) and the user sees an error and can retry. Not changed in this release (it would be a behaviour change); to be
addressed separately before many concurrent API clients are allowed.

## Installation and upgrade

Replace the program JAR (or the installed application) with 1.0.1. **No database script needs to be run**; 1.0.0 and
1.0.1 can be used side by side against the same database. Rollback: reinstall 1.0.0.

# Release Notes — Al Mahwar Store Management System 1.0.0

**نظام إدارة شركة المحور** — first production release of the desktop application.

| | |
|---|---|
| Application version | **1.0.0** |
| Database schema | **1.10.0** (`Schema_Info`) |
| Java | 17 |
| JavaFX | 21 |
| Database | Microsoft SQL Server 2017+ (JDBC) |
| Entry point | `com.almahwar.Launcher` (runnable JAR `almahwar-store-management-1.0.0-app.jar`) |

## Modules
- **Authentication & first administrator** — PBKDF2-hashed passwords, generic login errors, temporary lockout,
  one-time first-administrator setup (no default account or password), forced password change after a reset.
- **Role-based access control** — Admin, Accountant, Cashier, Storekeeper; permissions enforced in the service layer.
- **Dashboard** — role-specific KPIs, sales trend, best sellers, recent invoices, low stock.
- **Products** — catalogue, categories, brands, units, retail / wholesale prices; costs only with permission.
- **Inventory** — stock balances, movement history, audited manual adjustments; no negative stock.
- **Customers / Suppliers** — accounts ledger, credit limits, statements, collections and payments.
- **Purchases** — drafts and posting into stock, supplier account and cashbox.
- **POS / Sales** — barcode-first point of sale, retail / wholesale pricing, discounts, credit control, invoices.
- **Financial operations** — cashbox, expenses, deposits / withdrawals, party payments.
- **Returns** — sales and purchase returns against original documents.
- **Quotations** — draft → sent → accepted / rejected → converted to sale; printable.
- **Reports & analytics** — sales, profit, purchases, expenses, cashbox, inventory, parties, quotations, user
  activity; A4 print preview and Excel export.
- **Users & security** — user administration, password policy, admin safety rules, audit log.
- **Company settings** — company profile and logo on all printed documents, system settings, about screen.
- **Backup / verify / restore** — SQL Server full backups with history and verification; guarded restore with a
  verified safety backup and post-restore validation.
- **Health checks** — read-only startup check of configuration, server, database, schema version and critical tables.
- **External configuration** — JVM properties › `ALMAHWAR_*` environment variables › `config/application.properties`
  › safe bundled defaults; no credentials are bundled; TLS certificate validation on by default.
- **Arabic RTL user interface** with a unified design system (theme, components, status badges, tables, dialogs)
  and a consistent SVG icon system.

## Installation and upgrade
See `README.md` (sections "Setup" and "Production deployment") and `PRODUCTION_CHECKLIST.md`.
Upgrading an existing database: take a verified backup, then run `database/01_create_database.sql` — it only adds
what is missing and moves the schema to 1.10.0; data is preserved.

## Known non-blocking notes
- JavaFX logs "Unsupported JavaFX configuration" when started with `java -jar` (classpath launch); harmless.
- A backup interrupted by a forced program exit can remain listed as "قيد الإنشاء" in the backup history.
- Restoring over the live database needs an administrative SQL login (by design, see README).

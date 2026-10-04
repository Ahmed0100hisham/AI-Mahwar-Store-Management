# Al Mahwar Store Management System — نظام إدارة شركة المحور

Desktop system for a Kuwaiti company selling sanitary ware and plumbing supplies.
Java 17 · JavaFX 21 · FXML · CSS · SQL Server (JDBC) · Maven. Arabic RTL UI, currency KWD (3 decimals).

## Requirements
- JDK 17+
- Maven 3.9+
- SQL Server 2017+ with TCP/IP enabled on port 1433

## Setup
1. Create the database: run `database/01_create_database.sql` in SSMS.
2. Edit the `db.*` settings in `src/main/resources/application.properties`,
   or copy that file to `config/application.properties` next to the jar to override it without rebuilding.

Any setting can also be overridden with a JVM system property, e.g. `-Ddb.host=server -Ddb.password=...`.

## Run
```bash
mvn javafx:run                 # run from source
mvn package                    # build + unit tests (DB integration tests are skipped)
java -jar target/almahwar-store-management-1.0.0-SNAPSHOT-app.jar
```

## Database
`database/01_create_database.sql` creates `AlMahwarDB` (collation `Arabic_CI_AS`), all 24 tables and seed
data (roles, units, categories, cash customer). It is safe to run more than once.

- Money and quantities: `DECIMAL(18,3)` / `BigDecimal` scale 3, never FLOAT.
- `line_total` and `remaining_amount` are persisted computed columns; never insert or update them.
- Stock and customer/supplier balances change only via `adjustQuantity` / `adjustBalance` inside a
  `TransactionManager.inTransaction(...)` block, never through `update(...)`.

## Dashboard
All figures come from SQL Server (`DashboardDao`); "today"/"this month" use the database server's date.
Cards, sections and side-menu entries are filtered by permission (`DashboardService`, `NavigationItem`).
Net profit (month) = sales margin (line total − saved cost) − invoice discounts − returns − expenses.
The dashboard refreshes every `app.dashboard.refresh-seconds` (default 120).

**Demo data (test databases only):** `database/02_demo_data.sql` fills an *empty* database with 30 days of
sales, purchases, returns, payments and expenses. It refuses to run if products or sales already exist,
and needs an ADMIN user (create it from the app's first-run screen first).

## Login & permissions
- First start with an empty `Users` table shows a one-time form to create the system administrator.
- Passwords are stored only as PBKDF2-HMAC-SHA256 hashes (600,000 iterations, random salt).
- Roles → modules are defined in `service/RolePermissions` (ADMIN, CASHIER, STOREKEEPER, ACCOUNTANT).
  The seeded `MANAGER` role has no permissions and cannot log in.
- After `security.login.max-attempts` wrong passwords the account is locked for
  `security.login.lock-seconds`. The lock is stored in `Users.failed_login_attempts` /
  `Users.locked_until` (database server time), so it survives restarts and applies on every PC.
  An administrator can clear it with `UserDao.resetFailedLogins(userId)`.
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
  service/     business logic: interfaces + *Impl, SecurityContext, permissions
  util/        MoneyUtil, QuantityUtil, PasswordHasher (no JavaFX)
src/main/resources
  fxml/  css/  images/  application.properties
database/      SQL scripts
```

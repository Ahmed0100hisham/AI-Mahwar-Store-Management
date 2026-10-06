# Production checklist — نظام إدارة شركة المحور

Short and practical; details in README ("Production deployment", "Backup & restore"). No secrets in this file.

## Server (once)
- [ ] SQL Server 2017+ installed as a normal service, TCP/IP enabled, port reachable from the PCs (firewall)
- [ ] `database/01_create_database.sql` run; `SELECT schema_version FROM AlMahwarDB.dbo.Schema_Info` = **1.10.0**
- [ ] Application login created with least privilege (README script): `db_datareader`, `db_datawriter`,
      `db_backupoperator` in AlMahwarDB, `CREATE DATABASE` in master — **not** sa / sysadmin
- [ ] Separate administrative login (dbcreator/sysadmin) kept by the administrator for restores only
- [ ] TLS certificate installed on SQL Server and trusted by the PCs (name = `db.host`)
- [ ] Backup folder on the SQL Server machine created; SQL Server service account can write to it
- [ ] Off-server copy of the backup folder planned (the program does not copy or delete backups)

## Each PC
- [ ] Program installed (application image `AlMahwar.exe`, or Java 17 + JAR)
- [ ] `config\application.properties` created from `config\application.example.properties`; protected by Windows permissions
- [ ] `db.trust-server-certificate=false` (default) — `true` only on development machines
- [ ] Program starts: login screen without a database message (health check passed)
- [ ] Log folder writable: `%LOCALAPPDATA%\AlMahwar\logs\almahwar-0.0.log` exists after the first start

## First use
- [ ] First administrator created at the first start (strong password)
- [ ] Users created with their roles; nobody uses the administrator account for daily work
- [ ] Company profile and system settings filled in (الإعدادات)
- [ ] First backup created **and verified** (النسخ الاحتياطي)
- [ ] Restore procedure rehearsed once on a test database (README "Recovery procedure")

## Smoke test after install / upgrade
- [ ] Login, dashboard, POS, sales, products, inventory, purchases, customers, suppliers, quotations, returns,
      cashbox, expenses, reports, settings, users, backup screen open without errors
- [ ] A test backup is created and verified
- [ ] Logout returns to the login screen

## Upgrade
- [ ] Backup taken and verified before the upgrade
- [ ] Program closed on all PCs; upgrade script run; new schema version checked
- [ ] New program installed on every PC; health check passes

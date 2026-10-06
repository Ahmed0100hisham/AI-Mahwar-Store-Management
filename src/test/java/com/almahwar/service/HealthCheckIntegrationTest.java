package com.almahwar.service;

import com.almahwar.config.AppConfig;
import com.almahwar.config.DatabaseConnection;
import com.almahwar.dao.DatabaseHealthDao;
import com.almahwar.model.DatabaseHealth;
import com.almahwar.model.DatabaseHealth.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup health check against a real SQL Server. The program's database is only read (and must be HEALTHY,
 * unchanged by the check); every failure case runs on temporary databases {@code AlMahwarHealthTest_<random>}
 * built from the real schema script, then dropped. Enable with {@code -Ddb.it=true}.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthCheckIntegrationTest {

    private static final String MAIN_DB = DatabaseConnection.databaseName();
    private final String sfx = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final String schemaDb = "AlMahwarHealthTest_" + sfx;
    private final String emptyDb = "AlMahwarHealthEmpty_" + sfx;

    private static HealthCheckServiceImpl health(String db) {
        return new HealthCheckServiceImpl(AppConfig.getInstance()::databaseProblems, new DatabaseHealthDao(db), db,
                SettingsService.REQUIRED_SCHEMA_VERSION);
    }

    private static void exec(String db, String sql) {
        try (Connection con = DatabaseConnection.getConnection(db); Statement st = con.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String one(String db, String sql) {
        try (Connection con = DatabaseConnection.getConnection(db); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void drop(String db) {
        if (!db.startsWith("AlMahwarHealth")) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection("master");
             PreparedStatement ps = con.prepareStatement("""
                     DECLARE @db SYSNAME = ?;
                     IF DB_ID(@db) IS NOT NULL
                     BEGIN
                         DECLARE @on NVARCHAR(400) = N'ALTER DATABASE ' + QUOTENAME(@db) + N' SET ONLINE';
                         IF DATABASEPROPERTYEX(@db, 'Status') <> 'ONLINE' EXEC (@on);
                         DECLARE @s NVARCHAR(400) = N'ALTER DATABASE ' + QUOTENAME(@db) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ' + QUOTENAME(@db);
                         EXEC (@s);
                     END""")) {
            ps.setString(1, db);
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        String script = Files.readString(Path.of("database", "01_create_database.sql"), StandardCharsets.UTF_8)
                .replace("AlMahwarDB", schemaDb);
        try (Connection con = DatabaseConnection.getConnection("master"); Statement st = con.createStatement()) {
            StringBuilder batch = new StringBuilder();
            for (String line : script.split("\r?\n")) {
                if (line.trim().equalsIgnoreCase("GO")) {
                    if (!batch.toString().isBlank()) {
                        st.execute(batch.toString());
                    }
                    batch.setLength(0);
                } else {
                    batch.append(line).append('\n');
                }
            }
            st.execute("CREATE DATABASE " + emptyDb);   // generated identifier, letters / digits / underscore only
        }
    }

    @AfterAll
    void cleanUp() {
        drop(schemaDb);
        drop(emptyDb);
    }

    @Test
    void programsDatabaseIsHealthyAndTheCheckChangesNothing() {
        String audit = one(MAIN_DB, "SELECT CONCAT(COUNT(*), '/', MAX(log_id)) FROM dbo.Audit_Log");
        String users = one(MAIN_DB, "SELECT CONCAT(COUNT(*), '/', CHECKSUM_AGG(CHECKSUM(password_hash, is_active, "
                + "failed_login_attempts))) FROM dbo.Users");
        String stock = one(MAIN_DB, "SELECT CONCAT(COUNT(*), '/', SUM(quantity)) FROM dbo.Products");
        DatabaseHealth h = health(MAIN_DB).check();
        assertEquals(Status.HEALTHY, h.status(), h.message());
        assertTrue(h.canStart());
        assertTrue(h.usersExist());
        assertEquals(SettingsService.REQUIRED_SCHEMA_VERSION, h.schemaVersion());
        assertEquals(audit, one(MAIN_DB, "SELECT CONCAT(COUNT(*), '/', MAX(log_id)) FROM dbo.Audit_Log"), "read only");
        assertEquals(users, one(MAIN_DB, "SELECT CONCAT(COUNT(*), '/', CHECKSUM_AGG(CHECKSUM(password_hash, is_active, "
                + "failed_login_attempts))) FROM dbo.Users"));
        assertEquals(stock, one(MAIN_DB, "SELECT CONCAT(COUNT(*), '/', SUM(quantity)) FROM dbo.Products"));
    }

    @Test
    void missingDatabase() {
        DatabaseHealth h = health("AlMahwarHealthNoSuchDb_" + sfx).check();
        assertEquals(Status.DATABASE_UNAVAILABLE, h.status());
        assertTrue(h.message().contains("غير موجودة"), h.message());
        assertFalse(h.canStart());
    }

    @Test
    void emptyDatabaseWithoutSchema() {
        DatabaseHealth h = health(emptyDb).check();
        assertEquals(Status.SCHEMA_MISSING, h.status());
        assertFalse(h.canStart());
        assertEquals("0", one(emptyDb, "SELECT COUNT(*) FROM sys.tables"), "nothing was created by the check");
    }

    @Test
    void schemaStatesInOrder() {
        // a fresh schema without users: healthy, the first-administrator setup is offered
        DatabaseHealth fresh = health(schemaDb).check();
        assertEquals(Status.HEALTHY, fresh.status(), fresh.message());
        assertFalse(fresh.usersExist());

        exec(schemaDb, "UPDATE dbo.Schema_Info SET schema_version = '1.9.0'");
        DatabaseHealth older = health(schemaDb).check();
        assertEquals(Status.SCHEMA_OUTDATED, older.status());
        assertTrue(older.message().contains("1.9.0") && older.message().contains("سكربت الترقية"), older.message());
        assertFalse(older.canStart());
        assertEquals("1.9.0", one(schemaDb, "SELECT schema_version FROM dbo.Schema_Info"), "no automatic migration");

        exec(schemaDb, "UPDATE dbo.Schema_Info SET schema_version = '1.11.0'");
        DatabaseHealth newer = health(schemaDb).check();
        assertEquals(Status.SCHEMA_TOO_NEW, newer.status());
        assertFalse(newer.canStart());

        exec(schemaDb, "UPDATE dbo.Schema_Info SET schema_version = '1.10.0'");
        // users but no active administrator: allowed, with a warning
        exec(schemaDb, "INSERT INTO dbo.Users (username, password_hash, full_name, role_id, is_active) "
                + "SELECT 'health_cashier', 'x', N'كاشير', role_id, 1 FROM dbo.Roles WHERE role_code = 'CASHIER'");
        exec(schemaDb, "INSERT INTO dbo.Users (username, password_hash, full_name, role_id, is_active) "
                + "SELECT 'health_admin_off', 'x', N'مدير معطل', role_id, 0 FROM dbo.Roles WHERE role_code = 'ADMIN'");
        DatabaseHealth noAdmin = health(schemaDb).check();
        assertEquals(Status.NO_ACTIVE_ADMIN, noAdmin.status());
        assertTrue(noAdmin.canStart() && noAdmin.usersExist());
        assertEquals("0", one(schemaDb, "SELECT COUNT(*) FROM dbo.Users WHERE is_active = 1 AND username = 'health_admin_off'"),
                "the check never activates or creates an admin");

        exec(schemaDb, "DROP TABLE dbo.Backup_History");   // temporary database only
        DatabaseHealth missingTable = health(schemaDb).check();
        assertEquals(Status.CRITICAL_TABLE_MISSING, missingTable.status());
        assertTrue(missingTable.details().toString().contains("Backup_History"));
        assertFalse(missingTable.canStart());

        exec(schemaDb, "DROP TABLE dbo.Schema_Info");
        assertEquals(Status.SCHEMA_MISSING, health(schemaDb).check().status());

        exec("master", "ALTER DATABASE " + schemaDb + " SET OFFLINE WITH ROLLBACK IMMEDIATE");
        DatabaseHealth offline = health(schemaDb).check();
        assertEquals(Status.DATABASE_UNAVAILABLE, offline.status());
        assertTrue(offline.message().contains("OFFLINE"), offline.message());
        exec("master", "ALTER DATABASE " + schemaDb + " SET ONLINE");
    }
}

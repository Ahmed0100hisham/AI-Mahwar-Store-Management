package com.almahwar.service;

import com.almahwar.config.DatabaseConnection;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BackupHistoryDao;
import com.almahwar.dao.DatabaseBackupDao;
import com.almahwar.dao.DatabaseBackupDao.BackupHeader;
import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupInfo.Kind;
import com.almahwar.model.BackupInfo.Status;
import com.almahwar.model.BackupInfo.Verification;
import com.almahwar.model.BackupResult;
import com.almahwar.model.BackupVerificationResult;
import com.almahwar.model.RestoreResult;
import com.almahwar.model.Role;
import com.almahwar.util.PasswordHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Backup / verify / restore against a real SQL Server. Enable with {@code -Ddb.it=true}; the backup folder as SQL
 * Server sees it is {@code -Dbackup.it.directory} (default {@code /var/opt/mssql/backup}, the Docker test server).
 * <p>
 * Backups of the program's database are only <b>made</b> (never restored). Every restore runs on a temporary
 * database {@code AlMahwarRestoreTest_<random>} built from {@code database/01_create_database.sql}, and dropped
 * at the end.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupRestoreIntegrationTest {

    private static final String MAIN_DB = DatabaseConnection.databaseName();
    private static final String DIR = System.getProperty("backup.it.directory", "/var/opt/mssql/backup");
    private static final String PREFIX = "ItBackup";

    private final String tempDb = "AlMahwarRestoreTest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final TestSecurity security = new TestSecurity();
    private final TestSecurity tempSecurity = new TestSecurity();
    private final List<Integer> mainUsers = new ArrayList<>();
    private int mainAdmin;
    private int tempAdmin;
    private long mainUsersBefore;

    private BackupRestoreServiceImpl service(String database, String directory, TestSecurity sec) {
        return new BackupRestoreServiceImpl(new BackupRestoreServiceImpl.Config(true, directory, PREFIX, database,
                SettingsService.REQUIRED_SCHEMA_VERSION), new DatabaseBackupDao(database),
                new BackupHistoryDao(database, new AuditLogDao()), sec, sec::logout);
    }

    private BackupRestoreServiceImpl main() {
        return service(MAIN_DB, DIR, security);
    }

    private BackupRestoreServiceImpl temp() {
        return service(tempDb, DIR, tempSecurity);
    }

    // ---------- plain SQL helpers on any database ----------

    private static long count(String db, String sql, Object... params) {
        try (Connection con = DatabaseConnection.getConnection(db); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> texts(String db, String sql, Object... params) {
        try (Connection con = DatabaseConnection.getConnection(db); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int exec(String db, String sql, Object... params) {
        try (Connection con = DatabaseConnection.getConnection(db); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int insertAdmin(String db, String username) {
        try (Connection con = DatabaseConnection.getConnection(db);
             PreparedStatement ps = con.prepareStatement("""
                     INSERT INTO dbo.Users (username, password_hash, full_name, role_id, is_active)
                     OUTPUT INSERTED.user_id
                     SELECT ?, ?, N'مدير اختبار النسخ', role_id, 1 FROM dbo.Roles WHERE role_code = 'ADMIN'""")) {
            ps.setString(1, username);
            ps.setString(2, PasswordHasher.hash("Backup2026x".toCharArray(), 1_000));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds the temporary database with the real schema script (name replaced), batch by batch. */
    private void createTempDatabase() throws Exception {
        assertTrue(tempDb.startsWith("AlMahwarRestoreTest_") && !tempDb.equalsIgnoreCase(MAIN_DB));
        String script = Files.readString(Path.of("database", "01_create_database.sql"), StandardCharsets.UTF_8)
                .replace("AlMahwarDB", tempDb);
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
            if (!batch.toString().isBlank()) {
                st.execute(batch.toString());
            }
        }
    }

    private void dropTempDatabase() {
        if (!tempDb.startsWith("AlMahwarRestoreTest_")) {
            return;   // never anything else
        }
        try (Connection con = DatabaseConnection.getConnection("master");
             PreparedStatement ps = con.prepareStatement("""
                     DECLARE @db SYSNAME = ?;
                     IF DB_ID(@db) IS NOT NULL
                     BEGIN
                         DECLARE @s NVARCHAR(400) = N'ALTER DATABASE ' + QUOTENAME(@db) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ' + QUOTENAME(@db);
                         EXEC (@s);
                     END""")) {
            ps.setString(1, tempDb);
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<List<BackupHeader>> headers(String db, BackupInfo b) {
        return new DatabaseBackupDao(db).headers(BackupPolicy.serverPath(b.serverDirectory(), b.fileName()));
    }

    private String marker() {
        return texts(tempDb, "SELECT name_ar FROM dbo.Categories WHERE description = N'restore-marker'").toString();
    }

    @BeforeAll
    void setUp() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        mainAdmin = insertAdmin(MAIN_DB, "bk_admin_" + s);
        mainUsers.add(mainAdmin);
        mainUsersBefore = count(MAIN_DB, "SELECT COUNT(*) FROM dbo.Users");
        createTempDatabase();
        tempAdmin = insertAdmin(tempDb, "bk_temp_admin");
        exec(tempDb, "INSERT INTO dbo.Categories (name_ar, description) VALUES (N'قبل النسخة', N'restore-marker')");
    }

    @BeforeEach
    void asAdmin() {
        security.admin(mainAdmin);
        tempSecurity.admin(tempAdmin);
    }

    @AfterAll
    void cleanUp() {
        dropTempDatabase();
        exec(MAIN_DB, "DELETE FROM dbo.Audit_Log WHERE table_name = 'Backup_History' AND (user_id = ? OR description LIKE ?)",
                mainAdmin, "%" + PREFIX + "%");
        exec(MAIN_DB, "DELETE FROM dbo.Backup_History WHERE file_name LIKE ?", "%" + PREFIX + "%");
        for (Integer id : mainUsers) {
            exec(MAIN_DB, "DELETE FROM dbo.Audit_Log WHERE user_id = ?", id);
            exec(MAIN_DB, "DELETE FROM dbo.Users WHERE user_id = ?", id);
        }
    }

    // ======================= backup / verify on the program's database (non-destructive) =======================

    @Test
    @Order(1)
    void createBackupIsWrittenBySqlServerVerifiedAndRecorded() {
        BackupResult r = main().createBackup();
        BackupInfo b = r.backup();
        assertEquals(Status.COMPLETED, b.status());
        assertEquals(Verification.VERIFIED, b.verification());
        assertTrue(r.verified());
        assertTrue(b.fileName().matches(PREFIX + "_\\d{4}-\\d{2}-\\d{2}_\\d{6}(_\\d+)?\\.bak"), b.fileName());
        assertEquals(DIR, b.serverDirectory());
        assertEquals(MAIN_DB, b.databaseName());
        assertEquals(SettingsService.REQUIRED_SCHEMA_VERSION, b.schemaVersion());
        assertTrue(b.sizeBytes() > 0);
        assertEquals("admin", b.createdByName());
        assertNotNull(b.completedAt());
        assertNotNull(b.verifiedAt());
        // the file exists from SQL Server's point of view: exactly one full, copy-only, checksummed backup of the DB
        List<BackupHeader> sets = headers(MAIN_DB, b).orElseThrow();
        assertEquals(1, sets.size());
        assertEquals(MAIN_DB, sets.get(0).databaseName());
        assertTrue(sets.get(0).fullDatabase() && sets.get(0).copyOnly() && sets.get(0).hasChecksums());
        assertEquals(sets.get(0).sizeBytes(), b.sizeBytes());
        // history and audit
        assertTrue(main().history().stream().anyMatch(h -> h.backupId() == b.backupId()));
        List<String> actions = texts(MAIN_DB, "SELECT action FROM dbo.Audit_Log WHERE table_name = 'Backup_History' "
                + "AND record_id = ? ORDER BY log_id", String.valueOf(b.backupId()));
        assertEquals(List.of("BACKUP_STARTED", "BACKUP_COMPLETED", "BACKUP_VERIFIED"), actions);
        // verify again on request
        BackupVerificationResult v = main().verify(b.backupId());
        assertTrue(v.valid(), v.message());
        assertEquals(MAIN_DB, v.databaseInFile());
    }

    @Test
    @Order(2)
    void repeatedBackupsNeverOverwriteAFile() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            names.add(main().createBackup().backup().fileName());
        }
        assertEquals(3, names.stream().distinct().count(), names.toString());
        for (BackupInfo b : main().history()) {
            if (names.contains(b.fileName())) {
                assertEquals(1, headers(MAIN_DB, b).orElseThrow().size(), "one backup set per file: " + b.fileName());
            }
        }
    }

    @Test
    @Order(3)
    void invalidOrInaccessibleDirectoryFailsCleanly() {
        for (String dir : new String[]{"/nonexistent/almahwar-backups", "/root"}) {
            BackupException e = assertThrows(BackupException.class, () -> service(MAIN_DB, dir, security).createBackup());
            assertTrue(e.getMessage().contains("مجلد النسخ الاحتياطي"), e.getMessage());
            BackupInfo failed = main().history().stream().filter(b -> dir.equals(b.serverDirectory())).findFirst().orElseThrow();
            assertEquals(Status.FAILED, failed.status(), "never recorded as successful");
            assertEquals(Verification.NOT_VERIFIED, failed.verification());
            assertNotNull(failed.note());
            assertEquals(1, count(MAIN_DB, "SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'BACKUP_FAILED' AND record_id = ?",
                    String.valueOf(failed.backupId())));
            // a failed backup can be neither verified nor restored
            assertThrows(ValidationException.class, () -> main().verify(failed.backupId()));
            assertThrows(ValidationException.class, () -> main().restore(failed.backupId(), MAIN_DB));
        }
    }

    @Test
    @Order(4)
    void verificationFailsForMissingOrForeignBackups() {
        // a recorded backup whose file is gone
        exec(MAIN_DB, "INSERT INTO dbo.Backup_History (file_name, server_directory, database_name, status, schema_version) "
                + "VALUES (?, ?, ?, 'COMPLETED', '1.10.0')", PREFIX + "_2000-01-01_000000.bak", DIR, MAIN_DB);
        BackupInfo missing = main().history().stream().filter(b -> b.fileName().equals(PREFIX + "_2000-01-01_000000.bak"))
                .findFirst().orElseThrow();
        BackupVerificationResult v = main().verify(missing.backupId());
        assertFalse(v.valid());
        assertTrue(v.message().contains("غير موجود"), v.message());
        assertEquals(Verification.VERIFY_FAILED, main().history().stream()
                .filter(b -> b.backupId() == missing.backupId()).findFirst().orElseThrow().verification());
        assertEquals(1, count(MAIN_DB, "SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'BACKUP_VERIFY_FAILED' AND record_id = ?",
                String.valueOf(missing.backupId())));

        // a real, readable backup — but of another database — is refused
        BackupInfo other = new BackupRestoreServiceImpl(new BackupRestoreServiceImpl.Config(true, DIR, "ItForeign", tempDb,
                SettingsService.REQUIRED_SCHEMA_VERSION), new DatabaseBackupDao(tempDb),
                new BackupHistoryDao(tempDb, new AuditLogDao()), tempSecurity, tempSecurity::logout).createBackup().backup();
        exec(MAIN_DB, "INSERT INTO dbo.Backup_History (file_name, server_directory, database_name, status, schema_version) "
                + "VALUES (?, ?, ?, 'COMPLETED', '1.10.0')", other.fileName(), DIR, MAIN_DB);
        BackupInfo foreign = main().history().stream().filter(b -> b.fileName().equals(other.fileName())).findFirst().orElseThrow();
        BackupVerificationResult fv = main().verify(foreign.backupId());
        assertFalse(fv.valid());
        assertTrue(fv.message().contains("قاعدة بيانات أخرى"), fv.message());
        exec(MAIN_DB, "DELETE FROM dbo.Backup_History WHERE backup_id = ?", foreign.backupId());
    }

    @Test
    @Order(5)
    void operationsAreSerializedAcrossPcsAndThreads() throws Exception {
        // another PC holds the backup lock: every operation is refused at once, nothing waits
        AutoCloseable otherPc = new DatabaseBackupDao(MAIN_DB).tryLock().orElseThrow();
        try {
            assertTrue(assertThrows(BackupException.class, () -> main().createBackup()).getMessage().contains("قيد التنفيذ"));
            int any = main().history().get(0).backupId();
            assertThrows(BackupException.class, () -> main().verify(any));
            assertTrue(new DatabaseBackupDao(MAIN_DB).tryLock().isEmpty(), "a second lock is refused");
        } finally {
            otherPc.close();
        }
        // the lock is per database: the temporary database is not blocked by the program's database
        // two services (two PCs) at the same moment: each either succeeds or is refused as busy, never both broken
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BackupRestoreServiceImpl s = main();
            results.add(pool.submit((Callable<String>) () -> {
                start.await();
                try {
                    return s.createBackup().backup().fileName();
                } catch (BackupException busy) {
                    assertTrue(busy.getMessage().contains("قيد التنفيذ"), busy.getMessage());
                    return "BUSY";
                }
            }));
        }
        start.countDown();
        List<String> outcome = new ArrayList<>();
        for (Future<String> f : results) {
            outcome.add(f.get());
        }
        pool.shutdown();
        assertTrue(outcome.stream().anyMatch(o -> !o.equals("BUSY")), outcome.toString());
        assertTrue(outcome.contains("BUSY"), "simultaneous requests are refused, not queued: " + outcome);
        List<String> files = outcome.stream().filter(o -> !o.equals("BUSY")).toList();
        assertEquals(files.size(), files.stream().distinct().count());
        // the same service, a double click: the second call is refused while the first runs
        BackupRestoreServiceImpl one = main();
        ExecutorService two = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> clicks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            clicks.add(two.submit((Callable<String>) () -> {
                go.await();
                try {
                    return one.createBackup().backup().fileName();
                } catch (BackupException busy) {
                    return "BUSY";
                }
            }));
        }
        go.countDown();
        List<String> clickOutcome = List.of(clicks.get(0).get(), clicks.get(1).get());
        two.shutdown();
        assertEquals(1, clickOutcome.stream().filter(o -> o.equals("BUSY")).count(), clickOutcome.toString());
    }

    @Test
    @Order(6)
    void nonAdminsAreRefusedEvenAtTheServiceLayer() {
        int any = main().history().get(0).backupId();
        for (String role : List.of(Role.ACCOUNTANT, Role.CASHIER, Role.STOREKEEPER)) {
            security.as(role, mainAdmin);
            assertThrows(AccessDeniedException.class, () -> main().settings());
            assertThrows(AccessDeniedException.class, () -> main().history());
            assertThrows(AccessDeniedException.class, () -> main().createBackup());
            assertThrows(AccessDeniedException.class, () -> main().verify(any));
            assertThrows(AccessDeniedException.class, () -> main().restore(any, MAIN_DB));
        }
    }

    // ======================= restore, only on the temporary database =======================

    @Test
    @Order(10)
    void realRestoreBringsBackTheBackedUpDataAndEndsTheSession() {
        String mainStateBefore = new DatabaseBackupDao(MAIN_DB).state().toString();
        BackupInfo backup = temp().createBackup().backup();
        assertEquals(Verification.VERIFIED, backup.verification());
        // the data changes after the backup
        exec(tempDb, "UPDATE dbo.Categories SET name_ar = N'بعد النسخة' WHERE description = N'restore-marker'");
        exec(tempDb, "INSERT INTO dbo.Categories (name_ar, description) VALUES (N'صنف لاحق', N'restore-marker')");
        assertEquals("[بعد النسخة, صنف لاحق]", marker());

        // one click / a wrong confirmation never restores
        assertThrows(ValidationException.class, () -> temp().restore(backup.backupId(), null));
        assertThrows(ValidationException.class, () -> temp().restore(backup.backupId(), "RESTORE"));
        assertThrows(ValidationException.class, () -> temp().restore(backup.backupId(), tempDb.toLowerCase()));
        assertEquals("[بعد النسخة, صنف لاحق]", marker());
        assertTrue(tempSecurity.isLoggedIn());

        RestoreResult r = temp().restore(backup.backupId(), tempDb);

        // the backed-up data is back, the later changes are gone
        assertEquals("[قبل النسخة]", marker());
        assertEquals(backup.fileName(), r.restoredFile());
        assertTrue(r.safetyBackupFile().startsWith("PRE_RESTORE_" + PREFIX + "_"), r.safetyBackupFile());
        assertEquals(SettingsService.REQUIRED_SCHEMA_VERSION, r.schemaVersion());
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        assertTrue(r.checks().size() >= 6, r.checks().toString());
        // the database is online, multi-user, reachable, with its schema and an admin
        DatabaseBackupDao.DatabaseState state = new DatabaseBackupDao(tempDb).state();
        assertTrue(state.online());
        assertEquals("MULTI_USER", state.userAccess());
        assertEquals(SettingsService.REQUIRED_SCHEMA_VERSION, new DatabaseBackupDao(tempDb).schemaVersion().orElseThrow());
        assertEquals(1, count(tempDb, "SELECT COUNT(*) FROM dbo.Users WHERE user_id = ? AND is_active = 1", tempAdmin));
        // the session that restored has ended
        assertFalse(tempSecurity.isLoggedIn());

        // history and audit were carried into the restored (older) database
        tempSecurity.admin(tempAdmin);
        List<BackupInfo> history = temp().history();
        BackupInfo restored = history.stream().filter(h -> h.fileName().equals(backup.fileName())).findFirst().orElseThrow();
        assertEquals(Status.COMPLETED, restored.status());
        assertEquals(Verification.VERIFIED, restored.verification());
        BackupInfo safety = history.stream().filter(h -> h.fileName().equals(r.safetyBackupFile())).findFirst().orElseThrow();
        assertEquals(Kind.PRE_RESTORE, safety.kind());
        assertEquals(Status.COMPLETED, safety.status());
        assertEquals(Verification.VERIFIED, safety.verification());
        List<String> actions = texts(tempDb, "SELECT action FROM dbo.Audit_Log WHERE action IN "
                + "('RESTORE_REQUESTED', 'PRE_RESTORE_BACKUP_CREATED', 'RESTORE_COMPLETED') ORDER BY log_id");
        assertEquals(List.of("RESTORE_REQUESTED", "PRE_RESTORE_BACKUP_CREATED", "RESTORE_COMPLETED"), actions);

        // the safety backup really holds the state just before the restore: restoring it undoes the restore
        temp().restore(safety.backupId(), tempDb);
        assertEquals("[بعد النسخة, صنف لاحق]", marker());

        // the program's own database was never touched by any restore
        assertEquals(mainStateBefore, new DatabaseBackupDao(MAIN_DB).state().toString());
        assertEquals(mainUsersBefore, count(MAIN_DB, "SELECT COUNT(*) FROM dbo.Users"));
        assertEquals(0, count("master", "SELECT COUNT(*) FROM msdb.dbo.restorehistory WHERE destination_database_name = ? "
                + "AND restore_date > DATEADD(HOUR, -1, GETDATE())", MAIN_DB));
        assertTrue(count("master", "SELECT COUNT(*) FROM msdb.dbo.restorehistory WHERE destination_database_name = ?",
                tempDb) >= 2, "SQL Server's own restore record (msdb) survives the restore");
    }

    @Test
    @Order(11)
    void failedSafetyBackupPreventsTheRestore() {
        BackupInfo backup = temp().createBackup().backup();
        exec(tempDb, "UPDATE dbo.Categories SET name_ar = name_ar + N' *' WHERE description = N'restore-marker'");
        String before = marker();
        // the backup itself is readable in its folder, but safety backups would go to an inaccessible folder
        BackupRestoreServiceImpl noSafety = service(tempDb, "/nonexistent/almahwar-safety", tempSecurity);
        BackupException e = assertThrows(BackupException.class, () -> noSafety.restore(backup.backupId(), tempDb));
        assertTrue(e.getMessage().contains("النسخة الوقائية"), e.getMessage());
        assertEquals(before, marker(), "nothing was restored");
        assertTrue(tempSecurity.isLoggedIn(), "the session goes on: the database is unchanged");
        assertTrue(new DatabaseBackupDao(tempDb).state().online());
        assertEquals("MULTI_USER", new DatabaseBackupDao(tempDb).state().userAccess());
        assertEquals(1, count(tempDb, "SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'RESTORE_FAILED' AND description LIKE ?",
                "%النسخة الوقائية%"));
        assertEquals(1, count(tempDb, "SELECT COUNT(*) FROM dbo.Backup_History WHERE backup_kind = 'PRE_RESTORE' "
                + "AND status = 'FAILED' AND server_directory = ?", "/nonexistent/almahwar-safety"));
    }

    @Test
    @Order(12)
    void unverifiableIncompatibleOrForeignBackupsAreNeverRestored() {
        String before = marker();
        long safetyRows = count(tempDb, "SELECT COUNT(*) FROM dbo.Backup_History WHERE backup_kind = 'PRE_RESTORE'");
        // the recorded file is gone: verification fails first, nothing else happens
        exec(tempDb, "INSERT INTO dbo.Backup_History (file_name, server_directory, database_name, status, schema_version) "
                + "VALUES (?, ?, ?, 'COMPLETED', ?)", PREFIX + "_2001-01-01_000000.bak", DIR, tempDb,
                SettingsService.REQUIRED_SCHEMA_VERSION);
        int gone = temp().history().stream().filter(b -> b.fileName().startsWith(PREFIX + "_2001")).findFirst().orElseThrow().backupId();
        BackupException e = assertThrows(BackupException.class, () -> temp().restore(gone, tempDb));
        assertTrue(e.getMessage().contains("التحقق"), e.getMessage());
        assertEquals(safetyRows, count(tempDb, "SELECT COUNT(*) FROM dbo.Backup_History WHERE backup_kind = 'PRE_RESTORE'"),
                "no safety backup either");
        // an older schema
        BackupInfo real = temp().createBackup().backup();
        exec(tempDb, "UPDATE dbo.Backup_History SET schema_version = '1.9.0' WHERE backup_id = ?", real.backupId());
        assertTrue(assertThrows(ValidationException.class, () -> temp().restore(real.backupId(), tempDb))
                .getMessage().contains("أقدم"));
        // recorded for another database
        exec(tempDb, "UPDATE dbo.Backup_History SET schema_version = ?, database_name = ? WHERE backup_id = ?",
                SettingsService.REQUIRED_SCHEMA_VERSION, MAIN_DB, real.backupId());
        assertTrue(assertThrows(ValidationException.class, () -> temp().restore(real.backupId(), tempDb))
                .getMessage().contains("قاعدة بيانات أخرى"));
        // unknown id
        assertThrows(ValidationException.class, () -> temp().restore(999_999, tempDb));
        assertEquals(before, marker());
        assertTrue(tempSecurity.isLoggedIn());
    }

    @Test
    @Order(13)
    void restoreAndBackupNeverOverlap() throws Exception {
        BackupInfo backup = temp().createBackup().backup();
        AutoCloseable otherPc = new DatabaseBackupDao(tempDb).tryLock().orElseThrow();
        try {
            String before = marker();
            assertTrue(assertThrows(BackupException.class, () -> temp().restore(backup.backupId(), tempDb))
                    .getMessage().contains("قيد التنفيذ"));
            assertEquals(before, marker());
            assertTrue(tempSecurity.isLoggedIn());
        } finally {
            otherPc.close();
        }
        // two restores at once (double click): exactly one runs
        String typed = tempDb;
        BackupRestoreServiceImpl s = temp();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit((Callable<String>) () -> {
                go.await();
                try {
                    s.restore(backup.backupId(), typed);
                    return "RESTORED";
                } catch (BackupException busy) {
                    return busy.getMessage().contains("قيد التنفيذ") ? "BUSY" : "FAILED: " + busy.getMessage();
                } catch (AccessDeniedException ended) {
                    return "BUSY";   // the session had already ended by the time the second call checked it
                }
            }));
        }
        go.countDown();
        List<String> outcome = List.of(results.get(0).get(), results.get(1).get());
        pool.shutdown();
        assertEquals(1, outcome.stream().filter(o -> o.equals("RESTORED")).count(), outcome.toString());
        assertEquals(1, outcome.stream().filter(o -> o.equals("BUSY")).count(), outcome.toString());
        assertTrue(new DatabaseBackupDao(tempDb).state().online());
    }

    @Test
    @Order(20)
    void noSecretsInHistoryOrAudit() {
        String password = System.getProperty("db.password", "");
        for (String db : new String[]{MAIN_DB, tempDb}) {
            List<String> written = new ArrayList<>();
            written.addAll(texts(db, "SELECT CONCAT(description, N' ', old_values, N' ', new_values) FROM dbo.Audit_Log "
                    + "WHERE table_name = 'Backup_History' OR action LIKE 'RESTORE%' OR action LIKE 'BACKUP%'"));
            written.addAll(texts(db, "SELECT CONCAT(file_name, N' ', server_directory, N' ', note, N' ', created_by_name) "
                    + "FROM dbo.Backup_History"));
            assertFalse(written.isEmpty());
            for (String w : written) {
                if (!password.isEmpty()) {
                    assertFalse(w.contains(password), "database password written: " + w);
                }
                String lower = w.toLowerCase();
                assertFalse(lower.contains("password") || lower.contains("pbkdf2") || lower.contains("exception")
                        || lower.contains("at com.") || lower.contains("jdbc:"), w);
            }
        }
    }

    @Test
    @Order(21)
    void tempDatabaseIsNotTheProgramsDatabase() {
        assertNotEquals(MAIN_DB.toLowerCase(), tempDb.toLowerCase());
        if (!MAIN_DB.equals("AlMahwarDB")) {
            fail("integration tests expect the development database AlMahwarDB, got " + MAIN_DB);
        }
    }
}

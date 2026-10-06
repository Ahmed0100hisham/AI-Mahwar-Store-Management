package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BackupHistoryDao;
import com.almahwar.dao.DatabaseBackupDao;
import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupInfo.Kind;
import com.almahwar.model.BackupInfo.Status;
import com.almahwar.model.BackupInfo.Verification;
import com.almahwar.model.BackupSettings;
import com.almahwar.model.NavigationItem;
import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backup / restore rules without a database: permissions (also enforced by the service, before any SQL),
 * file names, the server-side folder, identifiers, the typed restore confirmation, status changes, schema
 * compatibility, configuration and the Arabic error wording. The real SQL Server behaviour is covered by
 * {@code BackupRestoreIntegrationTest}.
 */
class BackupRulesTest {

    private static final Set<Permission> BACKUP = EnumSet.of(Permission.BACKUP_VIEW, Permission.BACKUP_CREATE,
            Permission.BACKUP_VERIFY, Permission.BACKUP_RESTORE);

    private final TestSecurity security = new TestSecurity();

    /** A service whose DAOs are never reached by these tests (no connection is opened by their constructors). */
    private BackupRestoreServiceImpl service(boolean enabled, String directory) {
        return new BackupRestoreServiceImpl(new BackupRestoreServiceImpl.Config(enabled, directory, null, "AlMahwarDB",
                SettingsService.REQUIRED_SCHEMA_VERSION), new DatabaseBackupDao("AlMahwarDB"),
                new BackupHistoryDao("AlMahwarDB", new AuditLogDao()), security, security::logout);
    }

    @Test
    void onlyAdminHoldsBackupPermissions() {
        assertTrue(RolePermissions.forRole(Role.ADMIN).containsAll(BACKUP));
        for (String role : List.of(Role.ACCOUNTANT, Role.CASHIER, Role.STOREKEEPER, "MANAGER")) {
            for (Permission p : BACKUP) {
                assertFalse(RolePermissions.forRole(role).contains(p), role + " must not have " + p);
            }
        }
        User u = new User();
        u.setRoleCode(Role.CASHIER);
        assertFalse(NavigationItem.BACKUP.isVisibleTo(new UserSession(u, RolePermissions.forRole(Role.CASHIER),
                LocalDateTime.now())));
        u.setRoleCode(Role.ADMIN);
        assertTrue(NavigationItem.BACKUP.isVisibleTo(new UserSession(u, RolePermissions.forRole(Role.ADMIN),
                LocalDateTime.now())));
        assertEquals("النسخ الاحتياطي", UserServiceImpl.group(Permission.BACKUP_RESTORE));
    }

    @Test
    void serviceRefusesNonAdminsBeforeTouchingTheDatabase() {
        BackupRestoreServiceImpl s = service(true, "/var/opt/mssql/backup");
        for (String role : List.of(Role.ACCOUNTANT, Role.CASHIER, Role.STOREKEEPER)) {
            security.as(role, 900);
            assertThrows(AccessDeniedException.class, s::settings, role);
            assertThrows(AccessDeniedException.class, s::history, role);
            assertThrows(AccessDeniedException.class, s::createBackup, role);
            assertThrows(AccessDeniedException.class, () -> s.verify(1), role);
            assertThrows(AccessDeniedException.class, () -> s.restore(1, "AlMahwarDB"), role);
        }
        security.logout();
        assertThrows(AccessDeniedException.class, s::createBackup, "not logged in");
        // a restricted session (password change pending) has no permission at all
        User u = new User();
        u.setRoleCode(Role.ADMIN);
        SecurityContext restricted = () -> java.util.Optional.of(
                new UserSession(u, RolePermissions.forRole(Role.ADMIN), LocalDateTime.now(), true));
        BackupRestoreServiceImpl r = new BackupRestoreServiceImpl(new BackupRestoreServiceImpl.Config(true,
                "/var/opt/mssql/backup", null, "AlMahwarDB", "1.10.0"), new DatabaseBackupDao("AlMahwarDB"),
                new BackupHistoryDao("AlMahwarDB", new AuditLogDao()), restricted, () -> { });
        assertThrows(AccessDeniedException.class, () -> r.restore(1, "AlMahwarDB"));
    }

    @Test
    void restoreNeedsTheDatabaseNameTypedExactly() {
        BackupPolicy.checkConfirmation("AlMahwarDB", "AlMahwarDB");
        BackupPolicy.checkConfirmation("  AlMahwarDB ", "AlMahwarDB");
        for (String wrong : new String[]{null, "", "almahwardb", "RESTORE", "AlMahwar", "AlMahwarDB2", "نعم"}) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> BackupPolicy.checkConfirmation(wrong, "AlMahwarDB"), String.valueOf(wrong));
            assertNotNull(e.errorFor(BackupRestoreService.CONFIRMATION));
        }
        // checked by the service before anything else is done
        security.admin(1);
        assertThrows(ValidationException.class, () -> service(true, "/var/opt/mssql/backup").restore(1, "RESTORE"));
    }

    @Test
    void fileNamesAreClearUniquePerAttemptAndSafe() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 6, 17, 35, 0);
        assertEquals("AlMahwarDB_2026-10-06_173500.bak", BackupPolicy.fileName(Kind.MANUAL, "AlMahwarDB", t, 0));
        assertEquals("AlMahwarDB_2026-10-06_173500_2.bak", BackupPolicy.fileName(Kind.MANUAL, "AlMahwarDB", t, 1));
        assertEquals("PRE_RESTORE_AlMahwarDB_2026-10-06_173500.bak",
                BackupPolicy.fileName(Kind.PRE_RESTORE, "AlMahwarDB", t, 0));
        for (int i = 0; i < 50; i++) {
            assertTrue(BackupPolicy.isProgramFileName(BackupPolicy.fileName(Kind.MANUAL, "Al-Mahwar_DB", t, i)));
        }
        for (String bad : new String[]{null, "", "x.bak", "../AlMahwarDB_2026-10-06_173500.bak",
                "AlMahwarDB_2026-10-06_173500.bak'; DROP DATABASE AlMahwarDB--", "AlMahwarDB_2026-10-06_173500.txt",
                "/etc/AlMahwarDB_2026-10-06_173500.bak", "AlMahwarDB 2026-10-06 173500.bak"}) {
            assertFalse(BackupPolicy.isProgramFileName(bad), String.valueOf(bad));
        }
        assertEquals("AlMahwarDB", BackupPolicy.prefix(null, "AlMahwarDB"));
        assertEquals("Shop-1", BackupPolicy.prefix(" Shop-1 ", "AlMahwarDB"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.prefix("a/b", "AlMahwarDB"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.prefix("x'--", "AlMahwarDB"));
    }

    @Test
    void serverDirectoryMustBeAPlainAbsolutePathOfTheServer() {
        assertEquals("/var/opt/mssql/backup", BackupPolicy.directory(" /var/opt/mssql/backup/ "));
        assertEquals("D:\\SQLBackups", BackupPolicy.directory("D:\\SQLBackups\\"));
        assertEquals("D:\\", BackupPolicy.directory("D:\\"));
        assertEquals("\\\\fileserver\\backups\\almahwar", BackupPolicy.directory("\\\\fileserver\\backups\\almahwar"));
        assertEquals("E:/Backups", BackupPolicy.directory("E:/Backups"));
        for (String bad : new String[]{null, " ", "backups", "backups\\x", "C:", "/var/../etc", "D:\\a\\..\\b",
                "/tmp/x'; DROP DATABASE AlMahwarDB --", "C:\\a*b", "C:\\a?b", "C:\\a\"b", "/a;b", "\\\\server",
                "/" + "x".repeat(BackupPolicy.MAX_DIRECTORY_LENGTH)}) {
            assertThrows(IllegalArgumentException.class, () -> BackupPolicy.directory(bad), String.valueOf(bad));
        }
        assertEquals("/var/opt/mssql/backup/AlMahwarDB_2026-10-06_173500.bak",
                BackupPolicy.serverPath("/var/opt/mssql/backup", "AlMahwarDB_2026-10-06_173500.bak"));
        assertEquals("D:\\SQLBackups\\AlMahwarDB_2026-10-06_173500.bak",
                BackupPolicy.serverPath("D:\\SQLBackups\\", "AlMahwarDB_2026-10-06_173500.bak"));
        assertEquals("D:\\AlMahwarDB_2026-10-06_173500.bak",
                BackupPolicy.serverPath("D:\\", "AlMahwarDB_2026-10-06_173500.bak"));
        assertThrows(IllegalArgumentException.class, () -> BackupPolicy.serverPath("/var/backup", "../../etc/passwd"));
    }

    @Test
    void databaseIdentifiersArePlainNames() {
        assertEquals("AlMahwarDB", BackupPolicy.databaseName("AlMahwarDB"));
        assertEquals("AlMahwarRestoreTest_ab12", BackupPolicy.databaseName("AlMahwarRestoreTest_ab12"));
        for (String bad : new String[]{null, "", "AlMahwarDB]; DROP DATABASE master;--", "Al Mahwar", "db-1",
                "[AlMahwarDB]", "x".repeat(101)}) {
            assertThrows(IllegalArgumentException.class, () -> BackupPolicy.databaseName(bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> new DatabaseBackupDao(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new BackupRestoreServiceImpl(new BackupRestoreServiceImpl.Config(
                true, "/x", null, "bad name", "1.10.0"), null, null, security, () -> { }));
    }

    @Test
    void statusTransitionsAndUsability() {
        assertTrue(BackupPolicy.canMove(Status.CREATING, Status.COMPLETED));
        assertTrue(BackupPolicy.canMove(Status.CREATING, Status.FAILED));
        assertFalse(BackupPolicy.canMove(Status.FAILED, Status.COMPLETED), "a failed backup never becomes completed");
        assertFalse(BackupPolicy.canMove(Status.COMPLETED, Status.FAILED));
        assertFalse(BackupPolicy.canMove(Status.COMPLETED, Status.CREATING));
        assertFalse(BackupPolicy.canMove(Status.CREATING, Status.CREATING));

        assertNull(BackupPolicy.unusableReason(row(Status.COMPLETED, "AlMahwarDB_2026-10-06_173500.bak")));
        assertNotNull(BackupPolicy.unusableReason(row(Status.FAILED, "AlMahwarDB_2026-10-06_173500.bak")));
        assertNotNull(BackupPolicy.unusableReason(row(Status.CREATING, "AlMahwarDB_2026-10-06_173500.bak")));
        assertNotNull(BackupPolicy.unusableReason(row(Status.COMPLETED, "../../any.bak")));
        assertTrue(row(Status.COMPLETED, "x").usable());
        assertFalse(row(Status.FAILED, "x").usable());
    }

    private static BackupInfo row(Status status, String file) {
        return new BackupInfo(1, file, "/var/opt/mssql/backup", "AlMahwarDB", Kind.MANUAL, status,
                Verification.NOT_VERIFIED, "1.10.0", 1L, LocalDateTime.now(), "admin", null, null, null, null);
    }

    @Test
    void onlyBackupsOfTheProgramsSchemaAreRestored() {
        assertNull(BackupPolicy.incompatibility("1.10.0", "1.10.0"));
        assertNotNull(BackupPolicy.incompatibility(null, "1.10.0"));
        assertNotNull(BackupPolicy.incompatibility(" ", "1.10.0"));
        assertTrue(BackupPolicy.incompatibility("1.9.0", "1.10.0").contains("أقدم"), "older schema (numeric compare)");
        assertTrue(BackupPolicy.incompatibility("1.11.0", "1.10.0").contains("أحدث"));
    }

    @Test
    void configurationProblemsStopBackupsWithAClearMessage() {
        security.admin(1);
        BackupSettings off = service(false, "/var/opt/mssql/backup").settings();
        assertFalse(off.enabled());
        assertTrue(off.problem().contains("backup.enabled"));
        BackupException e = assertThrows(BackupException.class, () -> service(false, "/var/opt/mssql/backup").createBackup());
        assertTrue(e.getMessage().contains("معطّل"));

        BackupSettings relative = service(true, "backups").settings();
        assertFalse(relative.ready());
        assertTrue(relative.problem().contains("مسارًا كاملًا"));
        assertThrows(BackupException.class, () -> service(true, "C:\\bad*dir").createBackup());

        BackupSettings ok = service(true, "D:\\SQLBackups\\").settings();
        assertTrue(ok.ready());
        assertEquals("D:\\SQLBackups", ok.serverDirectory());
        assertFalse(ok.fromServerDefault());
    }

    @Test
    void bundledConfigurationHasBackupSettingsButNoCredentials() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("true", p.getProperty("backup.enabled"));
        assertEquals("", p.getProperty("backup.server-directory"), "the server folder is set per installation");
        assertEquals("", p.getProperty("db.password"));
        assertEquals("", p.getProperty("db.user"));
    }

    @Test
    void sqlServerErrorsBecomeShortArabicMessages() {
        assertTrue(BackupPolicy.describe(3201, "S0001", "Cannot open backup device '/x/y.bak'. Operating system error 5(Access is denied.).")
                .contains("صلاحية"));
        assertTrue(BackupPolicy.describe(3201, "S0001", "Operating system error 3(The system cannot find the path specified.).")
                .contains("غير موجود"));
        assertTrue(BackupPolicy.describe(3201, "S0001", "Operating system error 2(file not found)").contains("ملف"));
        assertTrue(BackupPolicy.describe(3241, "S0001", "The media family on device is incorrectly formed.").contains("تالف"));
        assertTrue(BackupPolicy.describe(3101, "S0001", "Exclusive access could not be obtained").contains("حصري"));
        assertTrue(BackupPolicy.describe(262, "S0001", "BACKUP DATABASE permission denied in database").contains("صلاحية"));
        assertTrue(BackupPolicy.describe(0, "08S01", "connection reset").contains("انقطع"));
        String generic = BackupPolicy.describe(50000, "S0001", "something");
        assertTrue(generic.contains("50000"));
        assertFalse(generic.contains("something"), "no technical text in the user message");
        assertEquals("1.0 KB", BackupRestoreServiceImpl.size(1024));
        assertEquals("18.1 MB", BackupRestoreServiceImpl.size(18_972_672));
    }
}

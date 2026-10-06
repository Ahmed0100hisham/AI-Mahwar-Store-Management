package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Permission;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserAccount;
import com.almahwar.model.UserAccount.Changes;
import com.almahwar.model.UserAccount.NewUser;
import com.almahwar.model.UserSession;
import com.almahwar.util.PasswordHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Users & security against a real SQL Server: account administration, password hashing, own password change,
 * admin reset with forced change, disabled accounts, lockout and expiry, last login, admin safety (also under
 * concurrency), permissions, audit without secrets, first-admin bootstrap. Enable with {@code -Ddb.it=true}.
 * The last-admin tests briefly deactivate the database's other admins and always restore them.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserSecurityIntegrationTest {

    private static final String PASS = "Mahwar2026";

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);
    private final UserDao userDao = new UserDao();
    private final RoleDao roleDao = new RoleDao();
    private final TestSecurity security = new TestSecurity();
    private final UserService users = new UserServiceImpl(userDao, roleDao, new AuditLogDao(), security);
    private final SessionManager session = SessionManager.getInstance();
    private final AuthService auth = new AuthServiceImpl(userDao, roleDao, new AuditLogDao(),
            new LoginAttemptTracker(3, Duration.ofSeconds(60), Clock.systemUTC()), session);
    private final List<Integer> ids = new ArrayList<>();
    private int adminId;

    private final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }

        List<Integer> ints(String sql, Object... params) {
            return queryList(sql, rs -> rs.getInt(1), params);
        }

        List<String> texts(String sql, Object... params) {
            return queryList(sql, rs -> rs.getString(1), params);
        }
    }

    private final Sql sql = new Sql();

    private int rawUser(String role, boolean active) {
        User u = new User();
        u.setUsername(("sec_" + role + "_" + suffix + "_" + ids.size()).toLowerCase());
        u.setPasswordHash(PasswordHasher.hash(PASS.toCharArray(), 1_000));
        u.setFullName("مستخدم أمان " + role);
        u.setRoleId(roleDao.findByCode(role).orElseThrow().getRoleId());
        u.setActive(active);
        int id = userDao.insert(u);
        ids.add(id);
        return id;
    }

    @BeforeAll
    void setUp() {
        adminId = rawUser(Role.ADMIN, true);
    }

    @BeforeEach
    void asAdmin() {
        security.admin(adminId);
    }

    @AfterEach
    void endSession() {
        auth.logout(AuthService.LogoutReason.USER);
    }

    @AfterAll
    void cleanUp() {
        restoreAdmins();
        for (Integer id : ids) {
            sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id = ?", id);
        }
        sql.exec("DELETE FROM dbo.Audit_Log WHERE description LIKE ?", "%" + suffix + "%");
        for (Integer id : ids) {
            sql.exec("DELETE FROM dbo.Users WHERE user_id = ?", id);
        }
    }

    private String uname(String tag) {
        return ("sec_" + tag + "_" + suffix).toLowerCase();
    }

    private UserAccount create(String tag, String role, boolean mustChange) {
        UserAccount a = users.create(new NewUser(uname(tag), "مستخدم " + tag + " " + suffix, null, null, role, mustChange),
                PASS.toCharArray(), PASS.toCharArray());
        ids.add(a.userId());
        return a;
    }

    private long audits(String action, int recordId) {
        return sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND record_id = ?", action, String.valueOf(recordId));
    }

    // ======================= administration =======================

    @Test
    void createStoresOnlyAHash() throws Exception {
        UserAccount a = create("Create", Role.CASHIER, false);
        assertEquals(uname("create"), a.username(), "stored trimmed and lower case");
        String hash = sql.texts("SELECT password_hash FROM dbo.Users WHERE user_id = ?", a.userId()).get(0);
        assertTrue(hash.startsWith("pbkdf2_sha256$600000$"), "PBKDF2, 600,000 iterations");
        assertFalse(hash.contains(PASS));
        assertTrue(PasswordHasher.verify(PASS.toCharArray(), hash));
        assertEquals(1, audits(AuditLogDao.USER_CREATED, a.userId()));
        assertTrue(a.active());
        assertNotNull(a.createdAt());

        // duplicate (any case), invalid input, unknown role
        assertThrows(ValidationException.class, () -> users.create(new NewUser(uname("CREATE").toUpperCase(), "x", null, null,
                Role.CASHIER, false), PASS.toCharArray(), PASS.toCharArray()), "duplicate username");
        assertThrows(ValidationException.class, () -> users.create(new NewUser("ab", "x", null, null, Role.CASHIER, false),
                PASS.toCharArray(), PASS.toCharArray()), "too short");
        assertThrows(ValidationException.class, () -> users.create(new NewUser(uname("p1"), "x", null, null, Role.CASHIER,
                false), "short1".toCharArray(), "short1".toCharArray()), "weak password");
        assertThrows(ValidationException.class, () -> users.create(new NewUser(uname("p2"), "x", null, null, Role.CASHIER,
                false), PASS.toCharArray(), "Mahwar2027".toCharArray()), "confirmation");
        assertThrows(ValidationException.class, () -> users.create(new NewUser("mahwar2026", "x", null, null, Role.CASHIER,
                false), PASS.toCharArray(), PASS.toCharArray()), "password = username");
        assertThrows(ValidationException.class, () -> users.create(new NewUser(uname("p3"), "x", null, null, Role.MANAGER,
                false), PASS.toCharArray(), PASS.toCharArray()), "only the four roles");
        assertThrows(ValidationException.class, () -> users.create(new NewUser(uname("p4"), " ", null, null, Role.CASHIER,
                false), PASS.toCharArray(), PASS.toCharArray()), "name required");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Users WHERE username LIKE ?", "sec_p_%" + suffix));
    }

    @Test
    void editRoleAndStatusAreAudited() throws Exception {
        UserAccount a = create("Edit", Role.CASHIER, false);
        UserAccount e = users.update(new Changes(a.userId(), "اسم جديد " + suffix, "22334455", "e@x.co", Role.ACCOUNTANT, true));
        assertEquals("اسم جديد " + suffix, e.fullName());
        assertEquals(Role.ACCOUNTANT, e.roleCode());
        assertEquals(1, audits(AuditLogDao.USER_UPDATED, a.userId()));
        assertEquals(1, audits(AuditLogDao.USER_ROLE_CHANGED, a.userId()));
        assertFalse(users.setActive(a.userId(), false).active());
        assertTrue(users.setActive(a.userId(), true).active());
        assertEquals(1, audits(AuditLogDao.USER_DISABLED, a.userId()));
        assertEquals(1, audits(AuditLogDao.USER_ENABLED, a.userId()));
        assertTrue(users.search(suffix, Role.ACCOUNTANT, true).stream().anyMatch(x -> x.userId() == a.userId()));
        assertTrue(users.search(suffix, Role.CASHIER, null).stream().noneMatch(x -> x.userId() == a.userId()));
    }

    // ======================= login, password, lockout =======================

    @Test
    void ownPasswordChangeEndsTheSession() throws Exception {
        UserAccount a = create("Own", Role.CASHIER, false);
        auth.login(a.username(), PASS.toCharArray());
        assertThrows(ValidationException.class, () -> auth.changePassword("Wrong1234".toCharArray(), "Newpass2026".toCharArray(),
                "Newpass2026".toCharArray()), "wrong current password");
        assertThrows(ValidationException.class, () -> auth.changePassword(PASS.toCharArray(), PASS.toCharArray(),
                PASS.toCharArray()), "must differ from the current one");
        assertTrue(session.isLoggedIn(), "failed attempts keep the session");
        auth.changePassword(PASS.toCharArray(), "Newpass2026".toCharArray(), "Newpass2026".toCharArray());
        assertFalse(session.isLoggedIn(), "the session ended: log in again");
        assertThrows(AuthenticationException.class, () -> auth.login(a.username(), PASS.toCharArray()), "old password");
        UserSession s = auth.login(a.username(), "Newpass2026".toCharArray());
        assertTrue(s.hasPermission(Permission.SALES_CREATE));
        assertEquals(1, audits(AuditLogDao.PASSWORD_CHANGED, a.userId()));
        assertNotNull(userDao.findById(a.userId()).orElseThrow().getPasswordChangedAt());
        assertNotNull(userDao.findById(a.userId()).orElseThrow().getLastLoginAt(), "last login recorded");
    }

    @Test
    void adminResetForcesAChangeAndTheRestrictedSessionCanDoNothing() throws Exception {
        UserAccount a = create("Reset", Role.ACCOUNTANT, false);
        assertThrows(ValidationException.class, () -> users.resetPassword(adminId, "Temp2026x".toCharArray(),
                "Temp2026x".toCharArray(), true), "an admin does not reset their own password");
        UserAccount r = users.resetPassword(a.userId(), "Temp2026x".toCharArray(), "Temp2026x".toCharArray(), true);
        assertTrue(r.mustChangePassword());
        assertEquals(1, audits(AuditLogDao.PASSWORD_RESET, a.userId()));

        UserSession restricted = auth.login(a.username(), "Temp2026x".toCharArray());
        assertTrue(restricted.isPasswordChangeRequired());
        assertTrue(restricted.getPermissions().isEmpty(), "no permission until the password is changed");
        assertThrows(AccessDeniedException.class, () -> new UserServiceImpl(userDao, roleDao, new AuditLogDao(), session)
                .search(null, null, null), "services refuse the restricted session");
        auth.changePassword("Temp2026x".toCharArray(), "Mine2026ok".toCharArray(), "Mine2026ok".toCharArray());
        UserSession normal = auth.login(a.username(), "Mine2026ok".toCharArray());
        assertFalse(normal.isPasswordChangeRequired());
        assertTrue(normal.hasPermission(Permission.REPORTS_PROFIT), "the accountant's permissions are back");
        assertFalse(userDao.findById(a.userId()).orElseThrow().isMustChangePassword());
    }

    @Test
    void disabledAccountsCannotLogIn() throws Exception {
        UserAccount a = create("Disabled", Role.CASHIER, false);
        users.setActive(a.userId(), false);
        AuthenticationException e = assertThrows(AuthenticationException.class, () -> auth.login(a.username(), PASS.toCharArray()));
        assertEquals(AuthenticationException.Reason.ACCOUNT_DISABLED, e.getReason());
        AuthenticationException wrong = assertThrows(AuthenticationException.class,
                () -> auth.login(a.username(), "Wrong1234".toCharArray()));
        assertEquals(AuthenticationException.Reason.INVALID_CREDENTIALS, wrong.getReason(),
                "a wrong password on a disabled account says only 'wrong credentials'");
        assertTrue(wrong.getMessage().startsWith("اسم المستخدم أو كلمة المرور غير صحيحة"));
    }

    @Test
    void lockoutExpiryAndUnlock() throws Exception {
        UserAccount a = create("Lock", Role.CASHIER, false);
        for (int i = 0; i < 3; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login(a.username(), "Wrong1234".toCharArray()));
        }
        AuthenticationException locked = assertThrows(AuthenticationException.class, () -> auth.login(a.username(), PASS.toCharArray()));
        assertEquals(AuthenticationException.Reason.ACCOUNT_LOCKED, locked.getReason(), "even the right password waits");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND record_id = ?",
                AuditLogDao.ACCOUNT_LOCKED, String.valueOf(a.userId())));
        assertTrue(users.findById(a.userId()).locked());

        // the lock expires by itself (temporary, no permanent lock-out)
        sql.exec("UPDATE dbo.Users SET locked_until = DATEADD(SECOND, -1, SYSDATETIME()) WHERE user_id = ?", a.userId());
        auth.login(a.username(), PASS.toCharArray());
        auth.logout(AuthService.LogoutReason.USER);
        assertEquals(0, userDao.findById(a.userId()).orElseThrow().getFailedLoginAttempts(), "counter cleared on success");

        // or an admin lifts it
        for (int i = 0; i < 3; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login(a.username(), "Wrong1234".toCharArray()));
        }
        security.admin(adminId);
        assertFalse(users.unlock(a.userId()).locked());
        assertEquals(1, audits(AuditLogDao.ACCOUNT_UNLOCKED, a.userId()));
        auth.login(a.username(), PASS.toCharArray());
    }

    // ======================= admin safety =======================

    private List<Integer> otherAdmins;

    /** Only this test's admins stay active (the others are restored afterwards). */
    private void isolateAdmins() {
        otherAdmins = sql.ints("SELECT u.user_id FROM dbo.Users u JOIN dbo.Roles r ON r.role_id = u.role_id "
                + "WHERE r.role_code = 'ADMIN' AND u.is_active = 1 AND u.username NOT LIKE ?", "%" + suffix + "%");
        for (Integer id : otherAdmins) {
            sql.exec("UPDATE dbo.Users SET is_active = 0 WHERE user_id = ?", id);
        }
    }

    private void restoreAdmins() {
        if (otherAdmins != null) {
            for (Integer id : otherAdmins) {
                sql.exec("UPDATE dbo.Users SET is_active = 1 WHERE user_id = ?", id);
            }
            otherAdmins = null;
        }
    }

    @Test
    void lastActiveAdminIsProtected() throws Exception {
        try {
            isolateAdmins();
            // only adminId is active now
            assertThrows(ValidationException.class, () -> users.setActive(adminId, false), "not yourself");
            security.admin(rawUser(Role.CASHIER, true));   // another person acting with admin rights
            assertThrows(ValidationException.class, () -> users.setActive(adminId, false), "the last active admin");
            assertThrows(ValidationException.class, () -> users.update(new Changes(adminId, "x", null, null,
                    Role.CASHIER, true)), "cannot be demoted either");
            assertTrue(userDao.findById(adminId).orElseThrow().isActive());
            assertEquals(Role.ADMIN, userDao.findById(adminId).orElseThrow().getRoleCode());
        } finally {
            restoreAdmins();
        }
    }

    @Test
    void concurrentChangesNeverRemoveTheLastAdmin() throws Exception {
        try {
            isolateAdmins();
            sql.exec("UPDATE dbo.Users SET is_active = 0 WHERE user_id = ?", adminId);
            int b = rawUser(Role.ADMIN, true);
            int c = rawUser(Role.ADMIN, true);
            int acting = rawUser(Role.CASHIER, true);
            // two admins deactivate each other's admin at the same moment: only one may succeed
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int target : new int[]{b, c}) {
                results.add(pool.submit(() -> {
                    TestSecurity own = new TestSecurity().admin(acting);
                    UserService svc = new UserServiceImpl(userDao, roleDao, new AuditLogDao(), own);
                    go.await();
                    try {
                        svc.setActive(target, false);
                        return true;
                    } catch (ValidationException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int ok = 0;
            for (Future<Boolean> f : results) {
                ok += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            pool.shutdown();
            assertEquals(1, ok, "exactly one of the two deactivations succeeds");
            assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Users u JOIN dbo.Roles r ON r.role_id = u.role_id "
                    + "WHERE r.role_code = 'ADMIN' AND u.is_active = 1"), "one active admin remains");
        } finally {
            sql.exec("UPDATE dbo.Users SET is_active = 1 WHERE user_id = ?", adminId);
            restoreAdmins();
        }
    }

    // ======================= permissions, audit, bootstrap =======================

    @Test
    void onlyAdminsManageUsers() throws Exception {
        UserAccount a = create("Target", Role.CASHIER, false);
        for (String role : List.of(Role.CASHIER, Role.ACCOUNTANT, Role.STOREKEEPER)) {
            security.as(role, rawUser(role, true));
            assertThrows(AccessDeniedException.class, () -> users.search(null, null, null), role + " list");
            assertThrows(AccessDeniedException.class, () -> users.create(new NewUser(uname("x" + role), "x", null, null,
                    Role.ADMIN, false), PASS.toCharArray(), PASS.toCharArray()), role + " create");
            assertThrows(AccessDeniedException.class, () -> users.update(new Changes(a.userId(), "x", null, null,
                    Role.ADMIN, true)), role + " edit / promote");
            assertThrows(AccessDeniedException.class, () -> users.resetPassword(a.userId(), "Hack2026x".toCharArray(),
                    "Hack2026x".toCharArray(), false), role + " reset another password");
            assertThrows(AccessDeniedException.class, () -> users.setActive(a.userId(), false), role);
            assertThrows(AccessDeniedException.class, () -> users.unlock(a.userId()), role);
        }
        assertTrue(PasswordHasher.verify(PASS.toCharArray(),
                sql.texts("SELECT password_hash FROM dbo.Users WHERE user_id = ?", a.userId()).get(0)), "unchanged");
    }

    @Test
    void auditNeverContainsPasswordsOrHashes() throws Exception {
        UserAccount a = create("Audit", Role.CASHIER, true);
        users.resetPassword(a.userId(), "Secret2026x".toCharArray(), "Secret2026x".toCharArray(), true);
        auth.login(a.username(), "Secret2026x".toCharArray());
        auth.changePassword("Secret2026x".toCharArray(), "Other2026y".toCharArray(), "Other2026y".toCharArray());
        List<String> texts = sql.texts("SELECT CONCAT(description, ' ', old_values, ' ', new_values) FROM dbo.Audit_Log "
                + "WHERE record_id = ? OR user_id = ?", String.valueOf(a.userId()), a.userId());
        assertFalse(texts.isEmpty());
        for (String t : texts) {
            for (String secret : List.of(PASS, "Secret2026x", "Other2026y", "pbkdf2")) {
                assertFalse(t.contains(secret), "audit leaks " + secret + ": " + t);
            }
        }
        // the audit report shows these entries, still without secrets
        security.admin(adminId);
        ReportService reports = new ReportServiceImpl(new com.almahwar.dao.ReportDao(), null, null, null, null, security, "x");
        var rows = reports.userActivity(ReportFilter.between(java.time.LocalDate.now().minusDays(1),
                java.time.LocalDate.now().plusDays(1)).search(a.username())).rows();
        assertTrue(rows.stream().noneMatch(r -> r.details() != null && (r.details().contains("Secret2026x") || r.details().contains("pbkdf2"))));
    }

    @Test
    void bootstrapNeverAddsASecondFirstAdmin() throws Exception {
        long before = userDao.count();
        assertTrue(auth.hasUsers());
        assertThrows(IllegalStateException.class, () -> auth.createInitialAdmin("مدير " + suffix, uname("boot"),
                PASS.toCharArray()), "users exist: no bootstrap");
        User u = new User();
        u.setUsername(uname("boot2"));
        u.setPasswordHash(PasswordHasher.hash(PASS.toCharArray(), 1_000));
        u.setFullName("x");
        u.setRoleId(roleDao.findByCode(Role.ADMIN).orElseThrow().getRoleId());
        assertTrue(TransactionManager.inTransaction(con -> userDao.insertFirst(con, u)).isEmpty(),
                "the atomic first-user insert creates nothing on a populated database");
        assertEquals(before, userDao.count());
    }
}

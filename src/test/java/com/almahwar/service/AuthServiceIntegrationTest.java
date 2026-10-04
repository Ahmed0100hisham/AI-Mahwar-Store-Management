package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;
import com.almahwar.service.DashboardService.Metric;
import com.almahwar.service.AuthenticationException.Reason;
import com.almahwar.util.PasswordHasher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login / logout / dashboard against a real database. Enable with
 * {@code -Ddb.it=true} (see DaoIntegrationTest for the full command).
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthServiceIntegrationTest {

    private static final String PASSWORD = "Mahwar2026";

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final UserDao userDao = new UserDao();
    private final RoleDao roleDao = new RoleDao();
    private final SessionManager session = SessionManager.getInstance();
    private final AuthService auth = new AuthServiceImpl(userDao, roleDao, new AuditLogDao(),
            new LoginAttemptTracker(3, Duration.ofSeconds(60), Clock.systemUTC()), session);
    private final List<Integer> userIds = new ArrayList<>();

    /** Gives the test access to BaseDao helpers for setup / cleanup SQL. */
    private static final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }
    }

    private final Sql sql = new Sql();

    private User createUser(String roleCode, String passwordHash, boolean active) {
        User u = new User();
        u.setUsername(roleCode.toLowerCase() + "_" + suffix + "_" + userIds.size());
        u.setPasswordHash(passwordHash);
        u.setFullName("مستخدم " + roleCode);
        u.setRoleId(roleDao.findByCode(roleCode).orElseThrow().getRoleId());
        u.setActive(active);
        userIds.add(userDao.insert(u));
        return u;
    }

    private User createUser(String roleCode) {
        return createUser(roleCode, PasswordHasher.hash(PASSWORD.toCharArray(), 1_000), true);
    }

    @AfterEach
    void endSession() {
        auth.logout(AuthService.LogoutReason.USER);
    }

    @AfterAll
    void cleanUp() {
        for (Integer id : userIds) {
            sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id = ?", id);
            sql.exec("DELETE FROM dbo.Users WHERE user_id = ?", id);
        }
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id IS NULL AND description LIKE ?", "%" + suffix + "%");
    }

    @Test
    @Order(1)
    void firstRunCreatesAdminOnlyOnce() {
        Assumptions.assumeFalse(auth.hasUsers(), "database already has users");

        IllegalArgumentException weak = assertThrows(IllegalArgumentException.class,
                () -> auth.createInitialAdmin("المدير", "admin_" + suffix, "short".toCharArray()));
        assertTrue(weak.getMessage().contains("8"));

        User admin = auth.createInitialAdmin("مدير النظام", "admin_" + suffix, "Admin2026".toCharArray());
        userIds.add(admin.getUserId());
        assertTrue(auth.hasUsers());
        assertTrue(admin.getPasswordHash().startsWith("pbkdf2_sha256$600000$"));
        assertThrows(IllegalStateException.class,
                () -> auth.createInitialAdmin("آخر", "other_" + suffix, "Admin2026".toCharArray()));
    }

    @Test
    void successfulLoginStartsSessionAndUpgradesHash() throws Exception {
        User cashier = createUser(Role.CASHIER);

        UserSession s = auth.login(" " + cashier.getUsername().toUpperCase() + " ", PASSWORD.toCharArray());
        assertTrue(session.isLoggedIn());
        assertEquals(Role.CASHIER, s.getUser().getRoleCode());
        assertNull(s.getUser().getPasswordHash(), "hash must not stay in memory");
        assertTrue(s.hasPermission(Permission.SALES));
        assertFalse(s.hasPermission(Permission.CASH));
        assertThrows(AccessDeniedException.class, () -> session.requirePermission(Permission.CASH));

        User stored = userDao.findById(cashier.getUserId()).orElseThrow();
        assertTrue(stored.getPasswordHash().startsWith("pbkdf2_sha256$600000$"), "1000-iteration hash upgraded");
        assertTrue(stored.getLastLoginAt() != null);
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND action = 'LOGIN'",
                cashier.getUserId()));

        auth.logout(AuthService.LogoutReason.USER);
        assertFalse(session.isLoggedIn());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND action = 'LOGOUT'",
                cashier.getUserId()));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameMessage() {
        User u = createUser(Role.STOREKEEPER);
        AuthenticationException wrongPw = assertThrows(AuthenticationException.class,
                () -> auth.login(u.getUsername(), "Wrong123".toCharArray()));
        AuthenticationException unknown = assertThrows(AuthenticationException.class,
                () -> auth.login("nobody_" + suffix, "Wrong123".toCharArray()));

        assertEquals(Reason.INVALID_CREDENTIALS, wrongPw.getReason());
        assertEquals(Reason.INVALID_CREDENTIALS, unknown.getReason());
        assertEquals(wrongPw.getMessage(), unknown.getMessage());
        assertFalse(session.isLoggedIn());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND action = 'LOGIN_FAILED'",
                u.getUserId()));
    }

    @Test
    void lockAfterRepeatedFailures() {
        User u = createUser(Role.ACCOUNTANT);
        assertThrows(AuthenticationException.class, () -> auth.login(u.getUsername(), "bad1".toCharArray()));
        assertThrows(AuthenticationException.class, () -> auth.login(u.getUsername(), "bad2".toCharArray()));
        AuthenticationException third = assertThrows(AuthenticationException.class,
                () -> auth.login(u.getUsername(), "bad3".toCharArray()));
        assertEquals(Reason.ACCOUNT_LOCKED, third.getReason());

        // Even the right password is refused while locked
        AuthenticationException locked = assertThrows(AuthenticationException.class,
                () -> auth.login(u.getUsername(), PASSWORD.toCharArray()));
        assertEquals(Reason.ACCOUNT_LOCKED, locked.getReason());

        // The lock is stored on the Users row ...
        User stored = userDao.findById(u.getUserId()).orElseThrow();
        assertEquals(3, stored.getFailedLoginAttempts());
        assertTrue(stored.isLocked() && stored.getLockedUntil() != null);

        // ... so a fresh AuthService (app restart, or another cashier PC) still refuses it
        AuthService otherPc = newAuthService(Duration.ofSeconds(60));
        assertEquals(Reason.ACCOUNT_LOCKED, assertThrows(AuthenticationException.class,
                () -> otherPc.login(u.getUsername(), PASSWORD.toCharArray())).getReason());

        // An administrator can unlock it
        userDao.resetFailedLogins(u.getUserId());
        assertDoesNotThrow(() -> otherPc.login(u.getUsername(), PASSWORD.toCharArray()));
    }

    @Test
    void lockExpiresAndSuccessfulLoginResetsCounter() throws Exception {
        AuthService shortLock = newAuthService(Duration.ofSeconds(2));
        User u = createUser(Role.STOREKEEPER);
        for (int i = 0; i < 3; i++) {
            assertThrows(AuthenticationException.class, () -> shortLock.login(u.getUsername(), "bad".toCharArray()));
        }
        assertTrue(userDao.findById(u.getUserId()).orElseThrow().isLocked());

        Thread.sleep(3_500);   // lock_until is DATETIME2(0): allow for whole-second rounding
        shortLock.login(u.getUsername(), PASSWORD.toCharArray());

        User stored = userDao.findById(u.getUserId()).orElseThrow();
        assertEquals(0, stored.getFailedLoginAttempts());
        assertNull(stored.getLockedUntil());
    }

    @Test
    void wrongPasswordAfterExpiredLockStartsANewCount() throws Exception {
        AuthService shortLock = newAuthService(Duration.ofSeconds(2));
        User u = createUser(Role.CASHIER);
        for (int i = 0; i < 3; i++) {
            assertThrows(AuthenticationException.class, () -> shortLock.login(u.getUsername(), "bad".toCharArray()));
        }
        Thread.sleep(3_500);

        AuthenticationException again = assertThrows(AuthenticationException.class,
                () -> shortLock.login(u.getUsername(), "bad".toCharArray()));
        assertEquals(Reason.INVALID_CREDENTIALS, again.getReason(), "first failure of a new count, not locked");
        User stored = userDao.findById(u.getUserId()).orElseThrow();
        assertEquals(1, stored.getFailedLoginAttempts());
        assertFalse(stored.isLocked());
    }

    @Test
    void unknownUsernamesAreThrottledLikeRealOnes() {
        String ghost = "ghost_" + suffix;
        assertThrows(AuthenticationException.class, () -> auth.login(ghost, "bad".toCharArray()));
        assertThrows(AuthenticationException.class, () -> auth.login(ghost, "bad".toCharArray()));
        assertEquals(Reason.ACCOUNT_LOCKED, assertThrows(AuthenticationException.class,
                () -> auth.login(ghost, "bad".toCharArray())).getReason());
    }

    private AuthService newAuthService(Duration lock) {
        return new AuthServiceImpl(userDao, roleDao, new AuditLogDao(),
                new LoginAttemptTracker(3, lock, Clock.systemUTC()), session);
    }

    @Test
    void disabledAccountAndRoleWithoutPermissionsAreRefused() {
        User disabled = createUser(Role.CASHIER, PasswordHasher.hash(PASSWORD.toCharArray(), 1_000), false);
        assertEquals(Reason.ACCOUNT_DISABLED, assertThrows(AuthenticationException.class,
                () -> auth.login(disabled.getUsername(), PASSWORD.toCharArray())).getReason());

        User manager = createUser(Role.MANAGER);
        assertEquals(Reason.NO_PERMISSIONS, assertThrows(AuthenticationException.class,
                () -> auth.login(manager.getUsername(), PASSWORD.toCharArray())).getReason());
        assertFalse(session.isLoggedIn());
    }

    @Test
    void emptyInputIsRejectedWithoutDatabaseAccess() {
        assertEquals(Reason.INVALID_INPUT, assertThrows(AuthenticationException.class,
                () -> auth.login("  ", "x".toCharArray())).getReason());
        assertEquals(Reason.INVALID_INPUT, assertThrows(AuthenticationException.class,
                () -> auth.login("user", new char[0])).getReason());
    }

    @Test
    void dashboardDependsOnRole() throws Exception {
        DashboardService dashboard = new DashboardServiceImpl(new DashboardDao(), session);
        DashboardService.TrendRange range = DashboardService.TrendRange.LAST_7_DAYS;
        assertThrows(AccessDeniedException.class, () -> dashboard.load(range), "requires login");

        // Cashier: sales figures and lists, no cash box, no stock list
        User cashier = createUser(Role.CASHIER);
        auth.login(cashier.getUsername(), PASSWORD.toCharArray());
        DashboardService.DashboardData c = dashboard.load(range);
        assertEquals(List.of(Metric.TODAY_SALES, Metric.TODAY_INVOICES, Metric.RECEIVABLES), metrics(c));
        assertTrue(c.recentInvoices() != null && c.salesTrend() != null && c.topProducts() != null);
        assertNull(c.lowStock());
        assertEquals(7, c.salesTrend().points().size(), "one point per day, gaps filled");
        auth.logout(AuthService.LogoutReason.USER);

        // Storekeeper: stock only
        User store = createUser(Role.STOREKEEPER);
        auth.login(store.getUsername(), PASSWORD.toCharArray());
        DashboardService.DashboardData s = dashboard.load(range);
        assertEquals(List.of(Metric.LOW_STOCK), metrics(s));
        assertTrue(s.lowStock() != null && s.topProducts() != null);
        assertNull(s.recentInvoices());
        assertNull(s.salesTrend());
        assertThrows(AccessDeniedException.class, () -> dashboard.loadSalesTrend(range));
        auth.logout(AuthService.LogoutReason.USER);

        // Accountant: money, no stock
        User accountant = createUser(Role.ACCOUNTANT);
        auth.login(accountant.getUsername(), PASSWORD.toCharArray());
        DashboardService.DashboardData a = dashboard.load(range);
        assertEquals(List.of(Metric.TODAY_SALES, Metric.MONTH_SALES, Metric.TODAY_INVOICES, Metric.NET_PROFIT,
                Metric.EXPENSES, Metric.CASH_BALANCE, Metric.RECEIVABLES, Metric.PAYABLES), metrics(a));
        assertNull(a.lowStock());
        assertTrue(a.metrics().stream().filter(v -> v.metric().isMoney())
                .allMatch(v -> v.value().scale() == 3), "KWD amounts keep 3 decimals");
        auth.logout(AuthService.LogoutReason.USER);

        // Admin: everything
        User admin = createUser(Role.ADMIN);
        auth.login(admin.getUsername(), PASSWORD.toCharArray());
        DashboardService.DashboardData all = dashboard.load(DashboardService.TrendRange.LAST_12_MONTHS);
        assertEquals(Metric.values().length, all.metrics().size());
        assertTrue(all.lowStock() != null && all.recentInvoices() != null
                && all.topProducts() != null && all.salesTrend() != null);
        assertEquals(12, all.salesTrend().points().size());
    }

    private static List<Metric> metrics(DashboardService.DashboardData data) {
        return data.metrics().stream().map(DashboardService.MetricValue::metric).toList();
    }
}

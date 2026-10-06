package com.almahwar.service;

import com.almahwar.config.ConfigProbe;
import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserAccount;
import com.almahwar.model.UserAccount.PermissionRow;
import com.almahwar.model.UserSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Users & security rules that need no database: credentials, admin safety, permission matrix, config, session. */
class UserSecurityRulesTest {

    // ---------- credentials ----------

    @Test
    void usernames() {
        assertEquals("ahmed.k", CredentialPolicy.normalizeUsername("  Ahmed.K "));
        assertNull(CredentialPolicy.normalizeUsername(null));
        for (String ok : List.of("abc", "ahmed.k", "user_1", "a-b-c", "x".repeat(50))) {
            assertDoesNotThrow(() -> CredentialPolicy.validateUsername(ok), ok);
        }
        for (String bad : new String[]{null, "", "ab", "has space", "عربي", "x".repeat(51), "a@b"}) {
            assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validateUsername(bad), String.valueOf(bad));
        }
    }

    @Test
    void passwordPolicy() {
        assertDoesNotThrow(() -> CredentialPolicy.validateNewPassword("Mahwar2026".toCharArray(),
                "Mahwar2026".toCharArray(), "ahmed"));
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validatePassword(null), "empty");
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validatePassword("Ab1".toCharArray()), "short");
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validatePassword("abcdefgh".toCharArray()), "no digit");
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validatePassword("12345678".toCharArray()), "no letter");
        char[] tooLong = new char[CredentialPolicy.MAX_PASSWORD_LENGTH + 1];
        Arrays.fill(tooLong, 'a');
        tooLong[0] = '1';
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validatePassword(tooLong), "too long");
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validateNewPassword("Ahmed123".toCharArray(),
                "Ahmed123".toCharArray(), "ahmed123"), "same as the username (any case)");
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.validateNewPassword("Mahwar2026".toCharArray(),
                "Mahwar2027".toCharArray(), "ahmed"), "confirmation differs");
    }

    // ---------- admin safety ----------

    private static User user(int id, String role, boolean active) {
        User u = new User();
        u.setUserId(id);
        u.setUsername("u" + id);
        u.setRoleCode(role);
        u.setActive(active);
        return u;
    }

    @Test
    void adminSafetyRules() {
        User admin1 = user(1, Role.ADMIN, true);
        User admin2 = user(2, Role.ADMIN, true);
        User cashier = user(3, Role.CASHIER, true);
        List<Integer> two = List.of(1, 2);
        List<Integer> one = List.of(1);

        assertThrows(ValidationException.class, () -> UserServiceImpl.checkSafety(two, admin1, Role.ADMIN, false, 1),
                "an admin cannot deactivate themself");
        assertThrows(ValidationException.class, () -> UserServiceImpl.checkSafety(two, admin1, Role.CASHIER, true, 1),
                "nor change their own role");
        assertDoesNotThrow(() -> UserServiceImpl.checkSafety(two, admin2, Role.ADMIN, false, 1), "another admin remains");
        assertDoesNotThrow(() -> UserServiceImpl.checkSafety(two, admin2, Role.ACCOUNTANT, true, 1));
        assertThrows(ValidationException.class, () -> UserServiceImpl.checkSafety(one, admin1, Role.ADMIN, false, 9),
                "the last active admin cannot be deactivated");
        assertThrows(ValidationException.class, () -> UserServiceImpl.checkSafety(one, admin1, Role.STOREKEEPER, true, 9),
                "nor demoted");
        assertDoesNotThrow(() -> UserServiceImpl.checkSafety(one, admin1, Role.ADMIN, true, 9), "editing it is fine");
        assertDoesNotThrow(() -> UserServiceImpl.checkSafety(one, cashier, Role.CASHIER, false, 1), "non-admins are free");
        assertDoesNotThrow(() -> UserServiceImpl.checkSafety(one, user(4, Role.ADMIN, false), Role.ADMIN, true, 1),
                "re-activating an admin");
    }

    // ---------- permissions ----------

    @Test
    void permissionMatrix() {
        List<PermissionRow> rows = UserServiceImpl.matrix();
        assertEquals(Permission.values().length, rows.size(), "every permission listed");
        Map<Permission, Set<String>> by = new java.util.EnumMap<>(Permission.class);
        rows.forEach(r -> by.put(r.permission(), r.roles()));
        for (Permission p : List.of(Permission.USERS_VIEW, Permission.USERS_CREATE, Permission.USERS_EDIT,
                Permission.USERS_RESET_PASSWORD, Permission.SETTINGS_VIEW, Permission.SETTINGS_EDIT)) {
            assertEquals(Set.of(Role.ADMIN), by.get(p), p + ": admin only");
        }
        for (Permission p : Permission.values()) {
            assertTrue(by.get(p).contains(Role.ADMIN), "admin holds " + p);
        }
        assertFalse(by.get(Permission.PRODUCT_COST).contains(Role.CASHIER), "no cost for the cashier");
        assertFalse(by.get(Permission.INVENTORY_ADJUST).contains(Role.CASHIER));
        assertFalse(by.get(Permission.SALES_PRICE_OVERRIDE).contains(Role.CASHIER));
        assertFalse(by.get(Permission.CASH_ADJUST).contains(Role.STOREKEEPER));
        assertFalse(by.get(Permission.REPORTS_EXPORT).contains(Role.CASHIER));
        assertTrue(rows.stream().noneMatch(r -> r.group() == null || r.group().isBlank()));
        assertEquals("الإدارة", UserServiceImpl.group(Permission.USERS_VIEW));
    }

    @Test
    void accountsNeverCarryAPassword() {
        for (RecordComponent c : UserAccount.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().contains("password") && !c.getName().equals("mustChangePassword")
                    && !c.getName().equals("passwordChangedAt"), "no password field: " + c.getName());
            assertFalse(c.getName().toLowerCase().contains("hash"), c.getName());
        }
    }

    @Test
    void sessionWithRequiredPasswordChangeHasNoPermissions() {
        User u = user(5, Role.ADMIN, true);
        UserSession restricted = new UserSession(u, RolePermissions.forRole(Role.ADMIN), LocalDateTime.now(), true);
        assertTrue(restricted.getPermissions().isEmpty());
        assertFalse(restricted.hasPermission(Permission.DASHBOARD));
        UserSession normal = new UserSession(u, RolePermissions.forRole(Role.ADMIN), LocalDateTime.now());
        assertTrue(normal.hasPermission(Permission.USERS_EDIT));
    }

    // ---------- configuration ----------

    @Test
    void configPrecedence() {
        assertEquals("ALMAHWAR_DB_PASSWORD", com.almahwar.config.AppConfig.environmentName("db.password"));
        assertEquals("ALMAHWAR_DB_LOGIN_TIMEOUT", com.almahwar.config.AppConfig.environmentName("db.login-timeout"));
        Properties files = new Properties();
        files.setProperty("db.password", "from-file");
        Map<String, String> env = Map.of("ALMAHWAR_DB_PASSWORD", "from-env");
        Map<String, String> sys = Map.of("db.password", "from-system");
        assertEquals("from-system", ConfigProbe.resolve("db.password", sys::get, env::get, files));
        assertEquals("from-env", ConfigProbe.resolve("db.password", k -> null, env::get, files));
        assertEquals("from-file", ConfigProbe.resolve("db.password", k -> null, k -> null, files));
        assertNull(ConfigProbe.resolve("db.missing", k -> null, k -> null, files));
    }

    @Test
    void bundledConfigurationHasNoCredentials() throws Exception {
        Properties p = new Properties();
        try (var in = UserSecurityRulesTest.class.getResourceAsStream("/application.properties")) {
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("", p.getProperty("db.password", ""), "no database password in the source code");
        assertEquals("", p.getProperty("db.user", ""), "no database user either");
    }
}

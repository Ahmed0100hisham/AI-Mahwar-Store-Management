package com.almahwar.api;

import com.almahwar.api.health.SchemaCompatibilityChecker;
import com.almahwar.api.support.TemporaryDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole API against a real SQL Server — on a temporary database created from the project's schema script
 * ({@link TemporaryDatabase}, schema 1.10.0) and dropped at the end. Users are created with the frozen desktop's own
 * {@code PasswordHasher}, exactly as the desktop stores them. AlMahwarDB is never used.
 * <p>
 * Runs only with {@code -Dalmahwar.it=true} and the {@code ALMAHWAR_IT_DB_*} environment variables.
 */
@EnabledIfSystemProperty(named = "almahwar.it", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlServerIntegrationTest {

    static final String PASSWORD = "Desktop#Pass2026";
    static final String SECRET;

    static {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        TemporaryDatabase.create();
        seed();
        registry.add("almahwar.db.host", TemporaryDatabase::host);
        registry.add("almahwar.db.port", TemporaryDatabase::port);
        registry.add("almahwar.db.name", () -> TemporaryDatabase.NAME);
        registry.add("almahwar.db.user", TemporaryDatabase::user);
        registry.add("almahwar.db.password", TemporaryDatabase::password);
        registry.add("almahwar.db.trust-server-certificate", () -> String.valueOf(TemporaryDatabase.trustServerCertificate()));
        registry.add("almahwar.api.jwt.secret", () -> SECRET);
        registry.add("almahwar.api.health.ready-cache", () -> "0s");
    }

    /** Users hashed by the desktop 1.0.0 class; a few categories / units / brands / products. */
    static void seed() throws Exception {
        String hash = com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray());
        String weak = com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray(), 1_000);
        user("it_admin", hash, "ADMIN", true, false, null);
        user("it_cashier", hash, "CASHIER", true, false, null);
        user("it_storekeeper", hash, "STOREKEEPER", true, false, null);
        user("it_accountant", hash, "ACCOUNTANT", true, false, null);
        user("it_disabled", hash, "CASHIER", false, false, null);
        user("it_mustchange", hash, "CASHIER", true, true, null);
        user("it_locked", hash, "CASHIER", true, false, 600);
        user("it_lockme", hash, "CASHIER", true, false, null);
        user("it_manager", hash, "MANAGER", true, false, null);
        user("it_weak", weak, "STOREKEEPER", true, false, null);
        user("it_revoke", hash, "CASHIER", true, false, null);
        user("it_rolechange", hash, "CASHIER", true, false, null);

        TemporaryDatabase.exec("INSERT INTO dbo.Brands (name_ar) VALUES (N'ماركة اختبار')");
        Object category = TemporaryDatabase.queryOne("SELECT MIN(category_id) FROM dbo.Categories");
        Object unit = TemporaryDatabase.queryOne("SELECT MIN(unit_id) FROM dbo.Units");
        Object brand = TemporaryDatabase.queryOne("SELECT MIN(brand_id) FROM dbo.Brands");
        for (int i = 1; i <= 25; i++) {
            TemporaryDatabase.exec("""
                    INSERT INTO dbo.Products (barcode, product_code, name_ar, name_en, category_id, brand_id, unit_id,
                                              purchase_price, sale_price, wholesale_price, quantity, minimum_stock, is_active)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "IT" + (1000 + i), "IT-" + String.format(java.util.Locale.ROOT, "%03d", i), "صنف اختبار " + String.format(java.util.Locale.ROOT, "%02d", i),
                    "Test item " + i, category, brand, unit, new BigDecimal("1.250").multiply(BigDecimal.valueOf(i)),
                    new BigDecimal("2.000").multiply(BigDecimal.valueOf(i)), new BigDecimal("1.900").multiply(BigDecimal.valueOf(i)),
                    BigDecimal.valueOf(i), BigDecimal.ONE, true);
        }
        TemporaryDatabase.exec("""
                INSERT INTO dbo.Products (product_code, name_ar, category_id, unit_id, purchase_price, sale_price,
                                          wholesale_price, quantity, minimum_stock, is_active)
                VALUES ('IT-OFF', N'صنف موقوف 100% خاص', ?, ?, 1, 2, 2, 0, 0, 0)
                """, category, unit);
    }

    static void user(String username, String hash, String role, boolean active, boolean mustChange, Integer lockSeconds)
            throws Exception {
        TemporaryDatabase.exec("""
                INSERT INTO dbo.Users (username, password_hash, full_name, role_id, is_active, must_change_password,
                                       failed_login_attempts, locked_until, password_changed_at)
                SELECT ?, ?, ?, r.role_id, ?, ?, ?, %s, SYSDATETIME()
                FROM dbo.Roles r WHERE r.role_code = ?
                """.formatted(lockSeconds == null ? "NULL" : "DATEADD(SECOND, " + lockSeconds.intValue() + ", SYSDATETIME())"),
                username, hash, "مستخدم " + username, active, mustChange, lockSeconds == null ? 0 : 5, role);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        TemporaryDatabase.drop();
        assertThat(TemporaryDatabase.NAME).isNotEqualToIgnoringCase("AlMahwarDB");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SchemaCompatibilityChecker checker;

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private String token(String username) throws Exception {
        MvcResult r = login(username, PASSWORD);
        assertThat(r.getResponse().getStatus()).as(username + " login").isEqualTo(200);
        return "Bearer " + r.getResponse().getContentAsString().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private static int intValue(String sql, Object... params) throws Exception {
        return ((Number) TemporaryDatabase.queryOne(sql, params)).intValue();
    }

    @Test
    @Order(1)
    void startsOnTheCompatibleSchemaAndIsReady() throws Exception {
        assertThat(checker.check().compatible()).isTrue();
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void desktopCreatedUsersLogInWithTheirRoles() throws Exception {
        for (String[] u : new String[][] {{"it_admin", "ADMIN"}, {"it_cashier", "CASHIER"},
                {"it_storekeeper", "STOREKEEPER"}, {"it_accountant", "ACCOUNTANT"}}) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", token(u[0])))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(u[0]))
                    .andExpect(jsonPath("$.roleCode").value(u[1]));
        }
        assertThat(intValue("SELECT COUNT(*) FROM dbo.Users WHERE username = 'it_admin' AND last_login_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(intValue("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'LOGIN' AND machine_name LIKE 'API/%'"))
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @Order(3)
    void productsWithCostOnlyForPermittedRolesAndPaging() throws Exception {
        String cashier = token("it_cashier");
        String body = mvc.perform(get("/api/v1/products?size=10&page=2").header("Authorization", cashier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(25))      // inactive product hidden
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("purchasePrice");

        mvc.perform(get("/api/v1/products?includeInactive=true").header("Authorization", cashier))
                .andExpect(jsonPath("$.totalItems").value(25));    // ignored without PRODUCTS

        String storekeeper = token("it_storekeeper");
        mvc.perform(get("/api/v1/products?sort=salePrice,desc&size=3").header("Authorization", storekeeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("IT-025"))
                .andExpect(jsonPath("$.items[0].salePrice").value("50.000"))
                .andExpect(jsonPath("$.items[0].purchasePrice").value("31.250"))
                .andExpect(jsonPath("$.items[1].code").value("IT-024"));
        mvc.perform(get("/api/v1/products?includeInactive=true&q=100%").header("Authorization", storekeeper))
                .andExpect(jsonPath("$.totalItems").value(1))      // "%" matched literally, inactive included
                .andExpect(jsonPath("$.items[0].code").value("IT-OFF"))
                .andExpect(jsonPath("$.items[0].active").value(false));

        mvc.perform(get("/api/v1/products?q=it-01&sort=code").header("Authorization", token("it_accountant")))
                .andExpect(jsonPath("$.totalItems").value(10))
                .andExpect(jsonPath("$.items[0].purchasePrice").exists());
    }

    @Test
    @Order(4)
    void wrongPasswordsLockTheSharedAccountRow() throws Exception {
        for (int i = 1; i <= 4; i++) {
            assertThat(login("it_lockme", "Wrong#" + i).getResponse().getStatus()).isEqualTo(401);
            assertThat(intValue("SELECT failed_login_attempts FROM dbo.Users WHERE username = 'it_lockme'")).isEqualTo(i);
        }
        MvcResult fifth = login("it_lockme", "Wrong#5");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(fifth.getResponse().getHeader("Retry-After"))).isBetween(295, 301);
        assertThat(intValue("SELECT COUNT(*) FROM dbo.Users WHERE username = 'it_lockme' AND locked_until > SYSDATETIME()"))
                .isEqualTo(1);
        // the correct password does not help while locked — on the desktop or the API
        assertThat(login("it_lockme", PASSWORD).getResponse().getStatus()).isEqualTo(429);
        assertThat(intValue("SELECT COUNT(*) FROM dbo.Audit_Log a JOIN dbo.Users u ON u.user_id = a.user_id "
                + "WHERE u.username = 'it_lockme' AND a.action = 'ACCOUNT_LOCKED' AND a.machine_name LIKE 'API/%'"))
                .isEqualTo(1);
        assertThat(intValue("SELECT COUNT(*) FROM dbo.Audit_Log a JOIN dbo.Users u ON u.user_id = a.user_id "
                + "WHERE u.username = 'it_lockme' AND a.action = 'LOGIN_FAILED'")).isEqualTo(6);
    }

    @Test
    @Order(5)
    void lockedDisabledMustChangeAndRoleWithoutPermissions() throws Exception {
        assertThat(login("it_locked", PASSWORD).getResponse().getContentAsString()).contains("ACCOUNT_LOCKED");
        assertThat(login("it_disabled", PASSWORD).getResponse().getContentAsString()).contains("ACCOUNT_DISABLED");
        assertThat(login("it_disabled", "Wrong#1").getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
        assertThat(login("it_manager", PASSWORD).getResponse().getContentAsString()).contains("NO_PERMISSIONS");

        String unknown = login("it_nobody", PASSWORD).getResponse().getContentAsString();
        String wrong = login("it_cashier", "Wrong#1").getResponse().getContentAsString();
        assertThat(unknown).contains("INVALID_CREDENTIALS");
        assertThat(wrong.replaceAll("\"(timestamp|requestId)\":\"[^\"]+\"", ""))
                .isEqualTo(unknown.replaceAll("\"(timestamp|requestId)\":\"[^\"]+\"", ""));
        assertThat(intValue("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id IS NULL AND action = 'LOGIN_FAILED' "
                + "AND description LIKE N'%it_nobody%'")).isEqualTo(1);

        String mustChange = token("it_mustchange");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", mustChange))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/products").header("Authorization", mustChange))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    @Order(6)
    void oldDesktopHashIsUpgradedAndStillWorksOnTheDesktop() throws Exception {
        token("it_weak");
        String stored = (String) TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE username = 'it_weak'");
        assertThat(stored).startsWith("pbkdf2_sha256$600000$");
        assertThat(com.almahwar.util.PasswordHasher.verify(PASSWORD.toCharArray(), stored)).isTrue();
    }

    @Test
    @Order(7)
    void changesInTheDatabaseApplyToExistingTokensAtOnce() throws Exception {
        String revoke = token("it_revoke");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", revoke)).andExpect(status().isOk());
        // a password change / admin reset (the desktop sets password_changed_at)
        TemporaryDatabase.exec("UPDATE dbo.Users SET password_changed_at = DATEADD(SECOND, 10, SYSDATETIME()) "
                + "WHERE username = 'it_revoke'");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", revoke))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        String disabled = token("it_rolechange");
        TemporaryDatabase.exec("UPDATE dbo.Users SET role_id = (SELECT role_id FROM dbo.Roles WHERE role_code = 'MANAGER') "
                + "WHERE username = 'it_rolechange'");
        mvc.perform(get("/api/v1/products").header("Authorization", disabled))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        TemporaryDatabase.exec("UPDATE dbo.Users SET is_active = 0 WHERE username = 'it_rolechange'");
        mvc.perform(get("/api/v1/products").header("Authorization", disabled))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    @Order(8)
    void incompatibleSchemaIsDetectedAndRefusedAtStartup() throws Exception {
        TemporaryDatabase.exec("UPDATE dbo.Schema_Info SET schema_version = '1.9.0' WHERE id = 1");
        try {
            assertThat(checker.check().status()).isEqualTo(SchemaCompatibilityChecker.Status.VERSION_MISMATCH);
            mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("NOT_READY"));

            Throwable failure = null;
            try {
                new SpringApplicationBuilder(AlMahwarApiApplication.class).web(WebApplicationType.SERVLET)
                        .logStartupInfo(false)
                        .run("--server.port=0", "--almahwar.db.host=" + TemporaryDatabase.host(),
                                "--almahwar.db.port=" + TemporaryDatabase.port(),
                                "--almahwar.db.name=" + TemporaryDatabase.NAME,
                                "--almahwar.db.user=" + TemporaryDatabase.user(),
                                "--almahwar.db.password=" + TemporaryDatabase.password(),
                                "--almahwar.db.trust-server-certificate=" + TemporaryDatabase.trustServerCertificate(),
                                "--almahwar.api.jwt.secret=" + SECRET)
                        .close();
            } catch (Throwable e) {
                failure = e;
            }
            assertThat(failure).isNotNull();
            StringBuilder messages = new StringBuilder();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                messages.append(t.getMessage());
            }
            assertThat(messages.toString()).contains("VERSION_MISMATCH").contains("1.9.0")
                    .doesNotContain(TemporaryDatabase.password().isEmpty() ? "\u0000" : TemporaryDatabase.password());
            assertThat(TemporaryDatabase.queryOne("SELECT schema_version FROM dbo.Schema_Info WHERE id = 1"))
                    .isEqualTo("1.9.0");   // nothing was migrated or repaired
        } finally {
            TemporaryDatabase.exec("UPDATE dbo.Schema_Info SET schema_version = '1.10.0' WHERE id = 1");
        }
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk());
    }
}

package com.almahwar.api.support;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.auth.AuthUserRepository.UserState;
import com.almahwar.api.health.DatabaseStatusRepository;
import com.almahwar.api.health.SchemaCompatibilityChecker;
import com.almahwar.api.product.ProductRepository;
import com.almahwar.api.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * The whole API (security, controllers, services, error handling) over MockMvc, with the repositories mocked: no
 * database is needed. One shared Spring context for every subclass (identical configuration).
 * <p>
 * The JWT secret is random per test run and the database settings are dummies: nothing real is in the tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiWebTestBase {

    public static final String TEST_JWT_SECRET;

    static {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        TEST_JWT_SECRET = Base64.getEncoder().encodeToString(key);
    }

    public static final LocalDateTime PASSWORD_CHANGED = LocalDateTime.of(2026, 1, 15, 10, 30, 0);

    public static final UserState ADMIN = state(1, "admin", "ADMIN", "مدير النظام", true, false);
    public static final UserState CASHIER = state(2, "cashier", "CASHIER", "كاشير", true, false);
    public static final UserState STOREKEEPER = state(3, "storekeeper", "STOREKEEPER", "أمين مخزن", true, false);
    public static final UserState ACCOUNTANT = state(4, "accountant", "ACCOUNTANT", "محاسب", true, false);
    public static final UserState MUST_CHANGE = state(5, "newcashier", "CASHIER", "كاشير", true, true);
    public static final UserState MANAGER = state(6, "manager", "MANAGER", "مدير", true, false);
    public static final UserState DISABLED = state(7, "disabled", "CASHIER", "كاشير", false, false);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("almahwar.db.host", () -> "db.invalid");
        registry.add("almahwar.db.user", () -> "test-user");
        registry.add("almahwar.db.password", () -> "test-only-not-a-real-password");
        registry.add("almahwar.api.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("almahwar.api.health.ready-cache", () -> "0s");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected TokenService tokens;

    @MockitoBean
    protected AuthUserRepository authUsers;
    @MockitoBean
    protected ProductRepository productRepository;
    @MockitoBean
    protected AuditLogRepository auditLog;
    /** API-owned persistence, if transactional, stays isolated here; core reads do not use a Spring transaction. */
    @MockitoBean
    protected PlatformTransactionManager transactionManager;
    /** Pre-stubbed as compatible: the startup check runs while the context is created, before any test method. */
    @TestBean
    protected DatabaseStatusRepository databaseStatus;

    static DatabaseStatusRepository databaseStatus() {
        DatabaseStatusRepository mock = Mockito.mock(DatabaseStatusRepository.class);
        stubCompatible(mock);
        return mock;
    }

    public static void stubCompatible(DatabaseStatusRepository mock) {
        when(mock.schemaVersion()).thenReturn(Optional.of(SchemaCompatibilityChecker.REQUIRED_SCHEMA_VERSION));
        when(mock.missingTables(anyCollection())).thenReturn(List.of());
    }

    @BeforeEach
    void knownUsers() {
        for (UserState u : List.of(ADMIN, CASHIER, STOREKEEPER, ACCOUNTANT, MUST_CHANGE, MANAGER, DISABLED)) {
            when(authUsers.findState(u.userId())).thenReturn(Optional.of(u));
        }
        Mockito.reset(databaseStatus);
        stubCompatible(databaseStatus);
    }

    /** A valid access token for the user, as the login would issue it. */
    protected String bearer(UserState user) {
        return "Bearer " + tokens.issue(user.userId(), user.passwordChangedAt()).value();
    }

    public static UserState state(int id, String username, String role, String roleName, boolean active,
                                  boolean mustChange) {
        return new UserState(id, username, "مستخدم " + username, active, mustChange, PASSWORD_CHANGED, role, roleName);
    }
}

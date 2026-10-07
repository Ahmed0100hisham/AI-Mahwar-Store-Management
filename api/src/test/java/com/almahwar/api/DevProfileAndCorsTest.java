package com.almahwar.api;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.health.DatabaseStatusRepository;
import com.almahwar.api.product.ProductRepository;
import com.almahwar.api.support.ApiWebTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dev profile publishes OpenAPI; CORS allows exactly the configured origin, without credentials. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevProfileAndCorsTest {

    private static final String APP = "http://localhost:5000";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiWebTestBase.sessionProperties(registry);
        registry.add("almahwar.db.host", () -> "db.invalid");
        registry.add("almahwar.db.user", () -> "test-user");
        registry.add("almahwar.db.password", () -> "test-only-not-a-real-password");
        registry.add("almahwar.api.jwt.secret", () -> ApiWebTestBase.TEST_JWT_SECRET);
        registry.add("almahwar.api.cors.allowed-origins", () -> APP);
    }

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuthUserRepository authUsers;
    @MockitoBean
    ProductRepository products;
    @MockitoBean
    AuditLogRepository audit;
    @MockitoBean
    PlatformTransactionManager transactionManager;
    @TestBean
    DatabaseStatusRepository databaseStatus;
    @TestBean
    com.almahwar.api.session.ApiSessionSchemaRepository sessionSchema;
    static com.almahwar.api.session.ApiSessionSchemaRepository sessionSchema() { return ApiWebTestBase.sessionSchema(); }

    static DatabaseStatusRepository databaseStatus() {
        DatabaseStatusRepository mock = Mockito.mock(DatabaseStatusRepository.class);
        ApiWebTestBase.stubCompatible(mock);
        return mock;
    }

    @Test
    void openApiDescribesTheVersionedEndpointsAndBearerAuth() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(doc).contains("/api/v1/auth/login").contains("/api/v1/products").contains("/api/v1/health")
                .contains("\"bearer\"").contains("ApiError")
                .doesNotContain("passwordHash").doesNotContain("password_hash").doesNotContain("jwt.secret");
    }

    @Test
    void corsAllowsOnlyTheConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/v1/products").header("Origin", APP)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", APP))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mvc.perform(options("/api/v1/products").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}

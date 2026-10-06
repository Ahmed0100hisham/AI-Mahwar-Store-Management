package com.almahwar.api;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.health.DatabaseStatusRepository;
import com.almahwar.api.product.ProductRepository;
import com.almahwar.api.security.TokenService;
import com.almahwar.api.support.ApiWebTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real connection pool and transaction manager against a database host that does not exist: a request needing the
 * database gets 503 SERVICE_UNAVAILABLE — not 500, and without host, driver or SQL text.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatabaseUnavailableTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("almahwar.db.host", () -> "db-host-that-does-not-exist.invalid");
        registry.add("almahwar.db.user", () -> "test-user");
        registry.add("almahwar.db.password", () -> "test-only-not-a-real-password");
        registry.add("almahwar.db.login-timeout-seconds", () -> "1");
        registry.add("almahwar.api.jwt.secret", () -> ApiWebTestBase.TEST_JWT_SECRET);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TokenService tokens;
    @MockitoBean
    AuthUserRepository authUsers;
    @MockitoBean
    ProductRepository products;
    @MockitoBean
    AuditLogRepository audit;
    @TestBean
    DatabaseStatusRepository databaseStatus;

    static DatabaseStatusRepository databaseStatus() {
        DatabaseStatusRepository mock = Mockito.mock(DatabaseStatusRepository.class);
        ApiWebTestBase.stubCompatible(mock);
        return mock;
    }

    @Test
    void transactionThatCannotGetAConnectionIs503() throws Exception {
        when(authUsers.findState(2)).thenReturn(Optional.of(ApiWebTestBase.CASHIER));
        String token = tokens.issue(2, ApiWebTestBase.PASSWORD_CHANGED).value();
        String body = mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("db-host-that-does-not-exist").doesNotContain("JDBC")
                .doesNotContain("SQLServer").doesNotContain("TCP/IP").doesNotContain("Hikari");
    }
}

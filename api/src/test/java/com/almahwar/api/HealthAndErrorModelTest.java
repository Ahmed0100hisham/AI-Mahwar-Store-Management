package com.almahwar.api;

import com.almahwar.api.support.ApiWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Application context, public health endpoints and the error format of unauthenticated / malformed requests. */
class HealthAndErrorModelTest extends ApiWebTestBase {

    @Autowired
    ApplicationContext context;

    @Test
    void applicationContextStarts() {
        assertThat(context.getBean(AlMahwarApiApplication.class)).isNotNull();
    }

    @Test
    void livenessIsPublicAndSaysOnlyUp() throws Exception {
        String body = mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void readinessReportsReadyWithoutDetails() throws Exception {
        String body = mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"status\":\"READY\"}");
    }

    @Test
    void readinessIsNotReadyOnIncompatibleSchemaAndLeaksNothing() throws Exception {
        when(databaseStatus.schemaVersion()).thenReturn(Optional.of("1.9.0"));
        String body = mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"status\":\"NOT_READY\"}");
    }

    @Test
    void readinessIsNotReadyWhenTablesMissingOrDatabaseDown() throws Exception {
        when(databaseStatus.missingTables(anyCollection())).thenReturn(List.of("Products"));
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("NOT_READY"));

        when(databaseStatus.schemaVersion()).thenThrow(new DataAccessResourceFailureException(
                "Login failed for user 'sa' on server 10.0.0.5"));
        String body = mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("sa").doesNotContain("10.0.0.5").doesNotContain("Login failed");
    }

    @Test
    void protectedEndpointWithoutTokenIs401InTheErrorFormat() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/v1/products"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void unknownPathsAreNotPublic() throws Exception {
        mvc.perform(get("/api/v1/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathForAuthenticatedUserIs404Json() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void openApiIsNotPublishedByDefault() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").header("Authorization", bearer(ADMIN))).andExpect(status().isNotFound());
    }

    @Test
    void malformedJsonIs400WithoutParserDetails() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"admin\", \"password\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("jackson").doesNotContainIgnoringCase("exception")
                .doesNotContain("line:").doesNotContain("column");
    }

    @Test
    void wrongMethodAndMediaType() throws Exception {
        mvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());   // GET is not public
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void callerRequestIdIsEchoedWhenSafeAndReplacedOtherwise() throws Exception {
        mvc.perform(get("/api/v1/health").header("X-Request-Id", "abc12345-mobile"))
                .andExpect(header().string("X-Request-Id", "abc12345-mobile"));
        String replaced = mvc.perform(get("/api/v1/health").header("X-Request-Id", "bad\r\nInjected: yes"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(replaced).doesNotContain("Injected").matches("[0-9a-f-]{36}");
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }
}

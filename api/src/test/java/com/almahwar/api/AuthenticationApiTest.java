package com.almahwar.api;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.auth.AuthUserRepository.LoginFailure;
import com.almahwar.api.auth.AuthUserRepository.LoginRow;
import com.almahwar.util.PasswordHasher;
import com.almahwar.api.support.ApiWebTestBase;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, token checks and the must-change-password gate, through HTTP (repositories mocked). */
class AuthenticationApiTest extends ApiWebTestBase {

    private static final String PASSWORD = "Correct#Horse2026";
    private static final String HASH = PasswordHasher.hash(PASSWORD.toCharArray());

    private static LoginRow row(int id, String username, String role, boolean active, boolean mustChange,
                                int failed, long lockSeconds) {
        return new LoginRow(id, username, HASH, "مستخدم " + username, active, mustChange, failed, lockSeconds > 0,
                lockSeconds, PASSWORD_CHANGED, role, role);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    // ---------- login ----------

    @Test
    void successfulLoginIssuesTokenAndFollowsDesktopSteps() throws Exception {
        when(authUsers.findForLogin("cashier")).thenReturn(Optional.of(row(2, "cashier", "CASHIER", true, false, 2, 0)));
        MvcResult result = login("  cashier ", PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"tokenType\":\"Bearer\"").contains("\"expiresIn\":900")
                .contains("\"mustChangePassword\":false").contains("SALES_POST")
                .doesNotContain(HASH).doesNotContain("pbkdf2").doesNotContainIgnoringCase("passwordHash")
                .doesNotContain(PASSWORD);

        verify(authUsers).resetFailedLogins(2);            // had 2 failed attempts
        verify(authUsers).updateLastLogin(2);
        verify(authUsers, never()).updatePasswordHash(anyInt(), anyString());   // already 600,000 iterations
        verify(auditLog).logQuietly(eq(2), eq(AuditLogRepository.LOGIN), isNull(), isNull(), contains("تسجيل دخول"), any());

        String token = body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.username").value("cashier"))
                .andExpect(jsonPath("$.roleCode").value("CASHIER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void tokenCarriesNoRoleOrSecretClaims() throws Exception {
        when(authUsers.findForLogin("admin")).thenReturn(Optional.of(row(1, "admin", "ADMIN", true, false, 0, 0)));
        String body = login("admin", PASSWORD).getResponse().getContentAsString();
        String token = body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(header).contains("HS256");
        assertThat(claims).contains("\"sub\":\"1\"").contains("\"iss\":\"almahwar-api\"").contains("almahwar-mobile")
                .contains("\"exp\"").contains("\"jti\"")
                .doesNotContain("ADMIN").doesNotContain("PERM").doesNotContain("pbkdf2").doesNotContain(PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        when(authUsers.findForLogin("cashier")).thenReturn(Optional.of(row(2, "cashier", "CASHIER", true, false, 0, 0)));
        when(authUsers.recordFailedLogin(2, 5, 300)).thenReturn(new LoginFailure(1, 0));
        when(authUsers.findForLogin("nobody")).thenReturn(Optional.empty());

        MvcResult wrong = login("cashier", "Wrong#Pass2026");
        MvcResult unknown = login("nobody", "Wrong#Pass2026");
        for (MvcResult r : List.of(wrong, unknown)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"INVALID_CREDENTIALS\"")
                    .doesNotContain("متبق").doesNotContain("cashier").doesNotContain("nobody");
        }
        assertThat(message(wrong)).isEqualTo(message(unknown));
        verify(authUsers).recordFailedLogin(2, 5, 300);   // counted on the shared Users row, desktop limits
        verify(auditLog).logQuietly(eq(2), eq(AuditLogRepository.LOGIN_FAILED), isNull(), isNull(), contains("كلمة مرور خاطئة"), any());
        verify(auditLog).logQuietly(isNull(), eq(AuditLogRepository.LOGIN_FAILED), isNull(), isNull(), contains("غير موجود"), any());
    }

    @Test
    void reachingTheLimitLocksTheAccount() throws Exception {
        when(authUsers.findForLogin("cashier")).thenReturn(Optional.of(row(2, "cashier", "CASHIER", true, false, 4, 0)));
        when(authUsers.recordFailedLogin(2, 5, 300)).thenReturn(new LoginFailure(5, 300));
        MvcResult r = login("cashier", "Wrong#Pass2026");
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("300");
        assertThat(r.getResponse().getContentAsString()).contains("ACCOUNT_LOCKED");
        verify(auditLog).logQuietly(eq(2), eq(AuditLogRepository.ACCOUNT_LOCKED), eq("Users"), eq("2"), anyString(), any());
    }

    @Test
    void lockedAccountIsRefusedEvenWithTheCorrectPassword() throws Exception {
        when(authUsers.findForLogin("cashier")).thenReturn(Optional.of(row(2, "cashier", "CASHIER", true, false, 5, 120)));
        MvcResult r = login("cashier", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
        assertThat(r.getResponse().getContentAsString()).contains("ACCOUNT_LOCKED").doesNotContain("accessToken");
        verify(authUsers, never()).updateLastLogin(anyInt());
        verify(authUsers, never()).resetFailedLogins(anyInt());
    }

    @Test
    void unknownUsernamesAreThrottledLikeRealAccounts() throws Exception {
        when(authUsers.findForLogin("ghost-user")).thenReturn(Optional.empty());
        for (int i = 1; i <= 4; i++) {
            assertThat(login("ghost-user", "x").getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult fifth = login("ghost-user", "x");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(429);
        assertThat(login("GHOST-USER", "x").getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void disabledAccountIsRevealedOnlyAfterTheCorrectPassword() throws Exception {
        when(authUsers.findForLogin("disabled")).thenReturn(Optional.of(row(7, "disabled", "CASHIER", false, false, 0, 0)));
        when(authUsers.recordFailedLogin(7, 5, 300)).thenReturn(new LoginFailure(1, 0));
        assertThat(login("disabled", "Wrong#Pass2026").getResponse().getContentAsString())
                .contains("INVALID_CREDENTIALS");
        MvcResult correct = login("disabled", PASSWORD);
        assertThat(correct.getResponse().getStatus()).isEqualTo(403);
        assertThat(correct.getResponse().getContentAsString()).contains("ACCOUNT_DISABLED").doesNotContain("accessToken");
    }

    @Test
    void roleWithoutPermissionsCannotLogIn() throws Exception {
        when(authUsers.findForLogin("manager")).thenReturn(Optional.of(row(6, "manager", "MANAGER", true, false, 0, 0)));
        MvcResult r = login("manager", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("NO_PERMISSIONS");
    }

    @Test
    void oldHashIsUpgradedOnLogin() throws Exception {
        // a 1,000-iteration hash of the same password, made the way the desktop's hash(password, iterations) does
        LoginRow old = new LoginRow(3, "storekeeper", WeakHash.of(PASSWORD), "x", true, false, 0, false, 0,
                PASSWORD_CHANGED, "STOREKEEPER", "STOREKEEPER");
        when(authUsers.findForLogin("storekeeper")).thenReturn(Optional.of(old));
        assertThat(login("storekeeper", PASSWORD).getResponse().getStatus()).isEqualTo(200);
        verify(authUsers).updatePasswordHash(eq(3), org.mockito.ArgumentMatchers.startsWith("pbkdf2_sha256$600000$"));
    }

    @Test
    void loginValidation() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'username')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"" + "x".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        verify(authUsers, never()).findForLogin(anyString());
    }

    // ---------- tokens ----------

    @Test
    void forgedOrBrokenTokensAre401() throws Exception {
        Instant now = Instant.now();
        byte[] otherKey = new byte[48];
        new SecureRandom().nextBytes(otherKey);
        String otherSigner = sign(otherKey, "almahwar-api", "almahwar-mobile", now, now.plusSeconds(600));
        byte[] realKey = Base64.getDecoder().decode(TEST_JWT_SECRET);
        String expired = sign(realKey, "almahwar-api", "almahwar-mobile", now.minusSeconds(3600), now.minusSeconds(300));
        String wrongAudience = sign(realKey, "almahwar-api", "someone-else", now, now.plusSeconds(600));
        String wrongIssuer = sign(realKey, "evil", "almahwar-mobile", now, now.plusSeconds(600));
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"1\",\"iss\":\"almahwar-api\",\"aud\":\"almahwar-mobile\",\"exp\":"
                        + now.plusSeconds(600).getEpochSecond() + ",\"pwv\":\"" + PASSWORD_CHANGED.toEpochSecond(java.time.ZoneOffset.UTC) + "\"}").getBytes()) + ".";
        for (String token : List.of("garbage", otherSigner, expired, wrongAudience, wrongIssuer, unsigned)) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        // a correctly signed control token works, so the failures above are the checks, not the helper
        mvc.perform(get("/api/v1/auth/me").header("Authorization",
                "Bearer " + sign(realKey, "almahwar-api", "almahwar-mobile", now, now.plusSeconds(600))))
                .andExpect(status().isOk());
    }

    @Test
    void disabledOrDeletedUserOrChangedPasswordRevokesExistingTokens() throws Exception {
        String token = bearer(CASHIER);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isOk());

        when(authUsers.findState(2)).thenReturn(Optional.of(state(2, "cashier", "CASHIER", "x", false, false)));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        when(authUsers.findState(2)).thenReturn(Optional.of(new com.almahwar.api.auth.AuthUserRepository.UserState(
                2, "cashier", "x", true, false, LocalDateTime.of(2026, 10, 7, 9, 0), "CASHIER", "x")));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        when(authUsers.findState(2)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void databaseDownWhileCheckingTokenIs503NotAnAccessDecision() throws Exception {
        String token = bearer(CASHIER);
        when(authUsers.findState(2)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "Connection refused to 10.1.2.3:1433"));
        String body = mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("10.1.2.3").doesNotContain("Connection refused");
    }

    // ---------- must change password ----------

    @Test
    void mustChangePasswordUserCanOnlySeeThemselves() throws Exception {
        when(authUsers.findForLogin("newcashier")).thenReturn(Optional.of(row(5, "newcashier", "CASHIER", true, true, 0, 0)));
        String body = login("newcashier", PASSWORD).getResponse().getContentAsString();
        assertThat(body).contains("\"mustChangePassword\":true").contains("\"permissions\":[]");

        String token = bearer(MUST_CHANGE);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.permissions").isEmpty());
        mvc.perform(get("/api/v1/products").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/v1/auth/me/").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // ---------- helpers ----------

    private static String message(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString().replaceAll(".*\"message\":\"([^\"]+)\".*", "$1");
    }

    private static String sign(byte[] key, String issuer, String audience, Instant issued, Instant expires) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject("1")
                .issuedAt(issued).expiresAt(expires).id("t")
                .claim("pwv", String.valueOf(PASSWORD_CHANGED.toEpochSecond(java.time.ZoneOffset.UTC))).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** A valid PBKDF2 hash with only 1,000 iterations (an "old" hash that must be upgraded). */
    static final class WeakHash {
        static String of(String password) throws Exception {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 1000, 256);
            byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "pbkdf2_sha256$1000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
        }
    }
}

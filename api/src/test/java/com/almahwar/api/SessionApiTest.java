package com.almahwar.api;

import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.session.ApiSessionRepository;
import com.almahwar.api.session.RefreshTokens;
import com.almahwar.api.support.ApiWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionApiTest extends ApiWebTestBase {
    @Test void accessRequiresASidAndALiveSession() throws Exception {
        when(sessionRepository.live(any(),anyInt(),anyString(),anyString(),anyBoolean())).thenReturn(false);
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(CASHIER)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        var encoder=new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(new javax.crypto.spec.SecretKeySpec(
                        java.util.Base64.getDecoder().decode(TEST_JWT_SECRET),"HmacSHA256")));
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject("2").issuer("almahwar-api")
                .audience(List.of("almahwar-mobile")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("pwv",com.almahwar.api.security.TokenService.passwordVersion(PASSWORD_CHANGED)).build();
        String noSid=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+noSid)).andExpect(status().isUnauthorized());
    }

    @Test void unavailableSessionStateIs503AndReadinessIsNotReady() throws Exception {
        when(sessionRepository.live(any(),anyInt(),anyString(),anyString(),anyBoolean()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-session-host"));
        var body=mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(CASHIER)))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-session-host");
        when(sessionSchema.compatible()).thenReturn(false);
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable());
    }

    @Test void malformedAndUnknownRefreshCredentialsHaveTheSameGenericError() throws Exception {
        when(sessionRepository.rotate(any(),any(),any())).thenReturn(new ApiSessionRepository.Rotation(
                ApiSessionRepository.RotationStatus.INVALID,null));
        for(String token:List.of("", "x", "' OR 1=1--", RefreshTokens.generate(),"x".repeat(10000))) {
            mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\""+token+"\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Test void restrictedUserHasExactMethodAndPathAllowlist() throws Exception {
        String token=bearer(MUST_CHANGE);
        mvc.perform(get("/api/v1/auth/me").header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization",token)).andExpect(status().isNoContent());
        for(var request:List.of(get("/api/v1/auth/sessions"),post("/api/v1/auth/logout-all"),
                delete("/api/v1/auth/sessions/"+UUID.randomUUID()),post("/api/v1/auth/me"),get("/api/v1/auth/change-password")))
            mvc.perform(request.header("Authorization",token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization",token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()); // reached DTO validation, not the restriction gate
    }

    @Test void sessionListIsSafeAndRevocationAlwaysScopesUser() throws Exception {
        Instant now=Instant.now();
        when(sessionRepository.list(2)).thenReturn(List.of(new ApiSessionRepository.Session(TEST_SID,2,"private-version",
                "private-fingerprint",false,"Android",now,now,now.plusSeconds(3600),now.plusSeconds(86400),null,true)));
        String result=mvc.perform(get("/api/v1/auth/sessions").header("Authorization",bearer(CASHIER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].current").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("private", "hash","token","userId","password");
        UUID other=UUID.randomUUID();
        mvc.perform(delete("/api/v1/auth/sessions/"+other).header("Authorization",bearer(CASHIER)))
                .andExpect(status().isNoContent());
        verify(sessionRepository).revoke(other,2,"USER_REVOKED");
        verify(auditLog,never()).logQuietly(any(),eq("API_SESSION_REVOKED"),any(),any(),any(),any());
    }

    @Test void newPasswordsUseSharedPolicyAndSensitiveDtosDoNotPrintSecrets() throws Exception {
        when(authUsers.findCredentials(2)).thenReturn(Optional.of(new AuthUserRepository.LoginRow(2,"cashier",LOGIN_HASH,
                "Cashier",true,false,0,false,0,PASSWORD_CHANGED,"CASHIER","Cashier")));
        for(String next:List.of("short1","onlyletters","123456789")) {
            mvc.perform(post("/api/v1/auth/change-password").header("Authorization",bearer(CASHIER))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\""+LOGIN_PASSWORD
                            +"\",\"newPassword\":\""+next+"\",\"confirmPassword\":\""+next+"\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(authUsers,never()).changePassword(anyInt(),anyString(),anyString());
        assertThat(new com.almahwar.api.auth.dto.RefreshRequest("secret-token").toString()).doesNotContain("secret-token");
        assertThat(new com.almahwar.api.auth.dto.ChangePasswordRequest("oldsecret","newsecret","newsecret").toString())
                .doesNotContain("oldsecret","newsecret");
    }

    @Test void deviceLabelsAreBoundedAndSafeBeforeAnyDatabaseWork() throws Exception {
        for(String label:List.of("x".repeat(101),"<script>","a\nb","a\u202eb")) {
            assertThatThrownBy(()->com.almahwar.api.session.SessionService.safeLabel(label))
                    .isInstanceOf(com.almahwar.api.error.ApiException.class);
        }
        assertThat(com.almahwar.api.session.SessionService.safeLabel("  هاتف أحمد  ")).isEqualTo("هاتف أحمد");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"cashier\",\"password\":\"x\",\"deviceLabel\":\""+"x".repeat(101)+"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void loginCannotIssueAnUntrackedSession() throws Exception {
        when(authUsers.findForLogin("cashier")).thenReturn(Optional.of(new AuthUserRepository.LoginRow(2,"cashier",LOGIN_HASH,
                "Cashier",true,false,0,false,0,PASSWORD_CHANGED,"CASHIER","Cashier")));
        when(sessionRepository.create(anyInt(),anyString(),anyString(),nullable(String.class),anyBoolean(),any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-session-host"));
        String body=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"cashier\",\"password\":\""+LOGIN_PASSWORD+"\"}"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("accessToken","refreshToken","private-session-host");
    }
}

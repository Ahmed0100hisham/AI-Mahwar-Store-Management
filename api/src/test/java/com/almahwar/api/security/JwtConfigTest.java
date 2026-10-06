package com.almahwar.api.security;

import com.almahwar.api.config.ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The signing key is mandatory and strong; token lifetime is bounded; tokens round-trip with the expected claims. */
class JwtConfigTest {

    private static String randomSecret(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static ApiProperties properties(String secret, Duration ttl) {
        return new ApiProperties(new ApiProperties.Jwt(secret, "almahwar-api", "almahwar-mobile", ttl),
                new ApiProperties.Login(5, 300), new ApiProperties.Cors(List.of()));
    }

    @Test
    void missingWeakOrInvalidSecretsStopTheApi() {
        for (String bad : new String[] {null, "", "   ", "CHANGE_ME", "not base64 !!", randomSecret(16),
                randomSecret(31), Base64.getEncoder().encodeToString(new byte[48])}) {
            assertThatThrownBy(() -> JwtConfig.signingKey(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(e -> {
                        if (bad != null && !bad.isBlank()) {
                            assertThat(e.getMessage()).doesNotContain(bad);   // never echo the secret
                        }
                    });
        }
        assertThat(JwtConfig.signingKey(randomSecret(32)).getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void accessTokenLifetimeIsBounded() {
        JwtConfig config = new JwtConfig();
        SecretKey key = JwtConfig.signingKey(randomSecret(48));
        assertThatThrownBy(() -> config.jwtDecoder(key, properties("x", Duration.ofHours(2))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtDecoder(key, properties("x", Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issuedTokenDecodesWithIdentityClaimsOnly() {
        JwtConfig config = new JwtConfig();
        ApiProperties props = properties(randomSecret(48), Duration.ofMinutes(15));
        SecretKey key = config.jwtSigningKey(props);
        JwtEncoder encoder = config.jwtEncoder(key);
        JwtDecoder decoder = config.jwtDecoder(key, props);
        LocalDateTime changed = LocalDateTime.of(2026, 3, 1, 8, 0, 5);

        TokenService.IssuedToken token = new TokenService(encoder, props).issue(42, changed);
        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getAudience()).containsExactly("almahwar-mobile");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("almahwar-api");
        assertThat(jwt.getClaimAsString(TokenService.PASSWORD_VERSION_CLAIM))
                .isEqualTo(String.valueOf(changed.toEpochSecond(ZoneOffset.UTC)));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "aud", "iss", "iat", "exp", "jti",
                TokenService.PASSWORD_VERSION_CLAIM);
        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    void expiredTokensAreRejected() {
        JwtConfig config = new JwtConfig();
        ApiProperties props = properties(randomSecret(48), Duration.ofMinutes(15));
        SecretKey key = config.jwtSigningKey(props);
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String old = new TokenService(config.jwtEncoder(key), props, past).issue(1, null).value();
        assertThatThrownBy(() -> config.jwtDecoder(key, props).decode(old))
                .hasMessageContaining("expired");
    }

    @Test
    void passwordVersionNeverNeedsClockAgreement() {
        assertThat(TokenService.passwordVersion(null)).isEqualTo("0");
        LocalDateTime t = LocalDateTime.of(2026, 10, 7, 1, 2, 3);
        assertThat(TokenService.passwordVersion(t)).isEqualTo(TokenService.passwordVersion(t));
        assertThat(TokenService.passwordVersion(t)).isNotEqualTo(TokenService.passwordVersion(t.plusSeconds(1)));
    }
}

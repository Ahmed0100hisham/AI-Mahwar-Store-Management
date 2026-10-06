package com.almahwar.api.security;

import com.almahwar.api.config.ApiProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Signing and verification of access tokens: standard JWS with HMAC-SHA256 (Nimbus JOSE through Spring Security — no
 * home-made cryptography). The key comes only from configuration and must be at least 256 bits of base64; there is no
 * default, so a missing or weak key stops the API at startup instead of running with a guessable one.
 * <p>
 * Accepted tokens: HS256 only, signature valid, not expired (60 s clock skew), issuer and audience as configured.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    static final int MIN_KEY_BYTES = 32;
    static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofHours(1);

    @Bean
    SecretKey jwtSigningKey(ApiProperties properties) {
        return signingKey(properties.jwt().secret());
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, ApiProperties properties) {
        Duration ttl = properties.jwt().accessTokenTtl();
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0) {
            throw new IllegalStateException("almahwar.api.jwt.access-token-ttl must be between 1s and "
                    + MAX_ACCESS_TOKEN_TTL + " (access tokens are not revocable before they expire)");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        String audience = properties.jwt().audience();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()), audienceValidator));
        return decoder;
    }

    /** Decodes and checks the configured secret; the message never contains the secret itself. */
    static SecretKey signingKey(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException("almahwar.api.jwt.secret is not set (ALMAHWAR_API_JWT_SECRET)");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("almahwar.api.jwt.secret must be base64 (e.g. openssl rand -base64 48)");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("almahwar.api.jwt.secret is too short: at least " + MIN_KEY_BYTES
                    + " random bytes are required (e.g. openssl rand -base64 48)");
        }
        if (allSame(key)) {
            throw new IllegalStateException("almahwar.api.jwt.secret is not random");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    private static boolean allSame(byte[] key) {
        for (byte b : key) {
            if (b != key[0]) {
                return false;
            }
        }
        return true;
    }
}

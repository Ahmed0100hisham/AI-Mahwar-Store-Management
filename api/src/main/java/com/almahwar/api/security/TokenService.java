package com.almahwar.api.security;

import com.almahwar.api.config.ApiProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Issues short-lived access tokens. Claims carry identity only — never a password, hash, role or permission:
 * <ul>
 *   <li>{@code sub}: the user id; {@code iss} / {@code aud}: this API and its mobile clients;</li>
 *   <li>{@code iat} / {@code exp}: lifetime ({@code almahwar.api.jwt.access-token-ttl}, 15 minutes by default);</li>
 *   <li>{@code jti}: unique token id; {@code sid}: the tracked API session checked on every request;</li>
 *   <li>{@code pwv}: the password version ({@code Users.password_changed_at}). A password change or an admin reset
 *       changes it, so every token issued before stops working at once.</li>
 * </ul>
 * Role, permissions, active and must-change-password are <b>not</b> trusted from the token: they are re-read from the
 * database on every request ({@link UserPrincipalLoader}), so a disabled user or a changed role takes effect at once.
 */
@Service
public class TokenService {

    public static final String PASSWORD_VERSION_CLAIM = "pwv";

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
        @Override
        public String toString() {
            return "IssuedToken[****, expiresAt=" + expiresAt + "]";
        }
    }

    private final JwtEncoder encoder;
    private final ApiProperties.Jwt settings;
    private final Clock clock;

    @Autowired
    public TokenService(JwtEncoder encoder, ApiProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    TokenService(JwtEncoder encoder, ApiProperties properties, Clock clock) {
        this.encoder = encoder;
        this.settings = properties.jwt();
        this.clock = clock;
    }

    public IssuedToken issue(int userId, LocalDateTime passwordChangedAt, UUID sessionId) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        Instant now = clock.instant();
        Instant expires = now.plus(settings.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .audience(List.of(settings.audience()))
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString())
                .claim(PASSWORD_VERSION_CLAIM, passwordVersion(passwordChangedAt))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expires, settings.accessTokenTtl().toSeconds());
    }

    /**
     * The password version stored in a token. Compared for equality only, with the value read from the same database
     * column, so the API's and the database server's clocks never need to agree.
     */
    public static String passwordVersion(LocalDateTime passwordChangedAt) {
        return passwordChangedAt == null ? "0" : String.valueOf(passwordChangedAt.toEpochSecond(ZoneOffset.UTC));
    }
}

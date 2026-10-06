package com.almahwar.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * API settings ({@code almahwar.api.*}). The JWT signing secret has no default and is validated at startup
 * ({@link com.almahwar.api.security.JwtConfig}); set {@code ALMAHWAR_API_JWT_SECRET}.
 */
@Validated
@ConfigurationProperties("almahwar.api")
public record ApiProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull Login login, @Valid @NotNull Cors cors) {

    /**
     * @param secret         base64 of at least 32 random bytes (HMAC-SHA256 key); never committed
     * @param accessTokenTtl lifetime of an access token: short, there is no server-side revocation list yet
     */
    public record Jwt(@NotBlank(message = "set ALMAHWAR_API_JWT_SECRET (base64, at least 32 random bytes)") String secret,
                      @DefaultValue("almahwar-api") @NotBlank String issuer,
                      @DefaultValue("almahwar-mobile") @NotBlank String audience,
                      @DefaultValue("15m") @NotNull Duration accessTokenTtl) {

        @Override
        public String toString() {
            return "Jwt[secret=****, issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl + "]";
        }
    }

    /**
     * Same limits as the desktop ({@code security.login.max-attempts} / {@code lock-seconds}): the lock is stored on
     * the Users row and shared by desktop and API, so both must use the same values.
     */
    public record Login(@DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
                        @DefaultValue("300") @Min(1) @Max(86400) int lockSeconds) {
    }

    /** Browser origins allowed to call the API (Flutter Web / tools). Empty = no cross-origin browser access. */
    public record Cors(@DefaultValue({}) List<String> allowedOrigins) {
    }
}

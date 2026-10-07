package com.almahwar.api.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Successful login. {@code mustChangePassword = true} means the token only opens {@code /auth/me} and the password
 * change and logout. Restricted login never returns a refresh credential.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
                            boolean mustChangePassword, CurrentUserResponse user, String refreshToken, UUID sid,
                            Instant refreshExpiresAt, Instant absoluteExpiresAt) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, expiresAt=" + expiresAt + ", user=" + user + "]";
    }
}

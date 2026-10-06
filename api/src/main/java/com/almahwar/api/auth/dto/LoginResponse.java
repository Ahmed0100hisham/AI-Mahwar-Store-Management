package com.almahwar.api.auth.dto;

import java.time.Instant;

/**
 * Successful login. {@code mustChangePassword = true} means the token only opens {@code /auth/me} and the password
 * change until the password is changed.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
                            boolean mustChangePassword, CurrentUserResponse user) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, expiresAt=" + expiresAt + ", user=" + user + "]";
    }
}

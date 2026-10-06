package com.almahwar.api.security;

import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.auth.AuthUserRepository.UserState;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Turns a verified token into the request's {@link ApiUser} by reading the user's <b>current</b> state from the
 * database. The token proves who the caller is; the database decides what they may do now:
 * <ul>
 *   <li>user deleted or disabled → {@code SESSION_REVOKED} (401);</li>
 *   <li>password changed or reset since the token was issued → {@code SESSION_REVOKED} (401);</li>
 *   <li>role changed → the new role's permissions apply at once;</li>
 *   <li>must change password → no permission (only the password change is reachable).</li>
 * </ul>
 * A lock (too many wrong passwords) does not end an already authenticated session — the desktop behaves the same;
 * it only stops new logins.
 */
@Component
public class UserPrincipalLoader implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AuthUserRepository users;

    public UserPrincipalLoader(AuthUserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        int userId;
        try {
            userId = Integer.parseInt(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Invalid subject");
        }
        Optional<UserState> state;
        try {
            state = users.findState(userId);
        } catch (DataAccessException e) {
            throw new UserStateUnavailableException(e);
        }
        UserState user = state.orElseThrow(() -> new SessionRevokedException("user not found"));
        if (!user.active()) {
            throw new SessionRevokedException("user disabled");
        }
        String tokenVersion = jwt.getClaimAsString(TokenService.PASSWORD_VERSION_CLAIM);
        if (!TokenService.passwordVersion(user.passwordChangedAt()).equals(tokenVersion)) {
            throw new SessionRevokedException("password changed");
        }
        Set<Permission> permissions = user.mustChangePassword() ? Set.of() : RolePermissions.forRole(user.roleCode());
        return new ApiAuthenticationToken(new ApiUser(user.userId(), user.username(), user.fullName(), user.roleCode(),
                user.roleName(), user.mustChangePassword(), permissions, jwt.getId()));
    }

    /**
     * The database could not be asked (answered 503 by {@link SecurityErrorHandlers}). Not an
     * {@code AuthenticationServiceException}: Spring Security re-throws those past the entry point.
     */
    public static class UserStateUnavailableException extends AuthenticationException {
        public UserStateUnavailableException(Throwable cause) {
            super("User state unavailable", cause);
        }
    }

    /** The token is genuine, but the account no longer allows it. */
    public static class SessionRevokedException extends AuthenticationException {
        public SessionRevokedException(String reason) {
            super(reason);
        }
    }
}

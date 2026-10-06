package com.almahwar.service;

import com.almahwar.model.User;
import com.almahwar.model.UserSession;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Desktop {@link SecurityContext}: holds the single logged-in user of this
 * application instance.
 * <p>
 * Only the authentication service starts and ends sessions. A future REST API
 * would not use this class; it would provide a per-request SecurityContext
 * built from the request's access token instead.
 */
public final class SessionManager implements SecurityContext {

    private static final SessionManager INSTANCE = new SessionManager();

    private volatile UserSession current;

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    void start(User user) {
        // a user who must change the password first gets a session without any permission
        current = new UserSession(user, RolePermissions.forRole(user.getRoleCode()), LocalDateTime.now(),
                user.isMustChangePassword());
    }

    void end() {
        current = null;
    }

    @Override
    public Optional<UserSession> getSession() {
        return Optional.ofNullable(current);
    }
}

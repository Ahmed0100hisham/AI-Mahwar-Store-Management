package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * The logged-in user and what they are allowed to do.
 * Immutable; a new instance is created on every login.
 */
public final class UserSession {

    private final User user;
    private final Set<Permission> permissions;
    private final LocalDateTime loginAt;
    private final boolean passwordChangeRequired;

    public UserSession(User user, Set<Permission> permissions, LocalDateTime loginAt) {
        this(user, permissions, loginAt, false);
    }

    /**
     * @param passwordChangeRequired the user must choose a new password before anything else: such a session
     *                               carries no permissions at all, so every service refuses until it is done
     */
    public UserSession(User user, Set<Permission> permissions, LocalDateTime loginAt, boolean passwordChangeRequired) {
        this.user = user;
        this.permissions = passwordChangeRequired ? Set.of() : Set.copyOf(permissions);
        this.loginAt = loginAt;
        this.passwordChangeRequired = passwordChangeRequired;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public User getUser() {
        return user;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public LocalDateTime getLoginAt() {
        return loginAt;
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }
}

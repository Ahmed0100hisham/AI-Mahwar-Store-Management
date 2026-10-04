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

    public UserSession(User user, Set<Permission> permissions, LocalDateTime loginAt) {
        this.user = user;
        this.permissions = Set.copyOf(permissions);
        this.loginAt = loginAt;
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

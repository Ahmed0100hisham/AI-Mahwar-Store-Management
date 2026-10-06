package com.almahwar.service;

import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;

import java.time.LocalDateTime;
import java.util.Optional;

/** A {@link SecurityContext} for tests whose user and role can be switched at any time. */
final class TestSecurity implements SecurityContext {

    private UserSession session;

    /** Logs in as {@code roleCode} with the role's real permissions from {@link RolePermissions}. */
    TestSecurity as(String roleCode, int userId) {
        User user = new User();
        user.setUserId(userId);
        user.setUsername(roleCode.toLowerCase());
        user.setFullName("مستخدم " + roleCode);
        user.setRoleCode(roleCode);
        session = new UserSession(user, RolePermissions.forRole(roleCode), LocalDateTime.now());
        return this;
    }

    /** Logs in with exactly these permissions (e.g. a cashier without a discount permission). */
    TestSecurity with(int userId, java.util.Set<com.almahwar.model.Permission> permissions) {
        User user = new User();
        user.setUserId(userId);
        user.setUsername("custom");
        user.setFullName("مستخدم بصلاحيات مخصصة");
        user.setRoleCode(Role.CASHIER);
        session = new UserSession(user, permissions, LocalDateTime.now());
        return this;
    }

    TestSecurity admin(int userId) {
        return as(Role.ADMIN, userId);
    }

    void logout() {
        session = null;
    }

    @Override
    public Optional<UserSession> getSession() {
        return Optional.ofNullable(session);
    }
}

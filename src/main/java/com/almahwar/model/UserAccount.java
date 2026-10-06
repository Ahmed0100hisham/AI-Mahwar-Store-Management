package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * A user as the user-management screen sees it. There is deliberately no password or hash field: what the
 * service returns can never show or leak one.
 *
 * @param locked              temporarily locked after too many wrong passwords ({@code lockSecondsRemaining} left)
 * @param mustChangePassword  the next login must choose a new password (after an admin reset)
 */
public record UserAccount(int userId, String username, String fullName, String phone, String email,
                          String roleCode, String roleName, boolean active, boolean locked, long lockSecondsRemaining,
                          boolean mustChangePassword, LocalDateTime lastLoginAt, LocalDateTime passwordChangedAt,
                          LocalDateTime createdAt) {

    public static UserAccount of(User u) {
        return new UserAccount(u.getUserId(), u.getUsername(), u.getFullName(), u.getPhone(), u.getEmail(),
                u.getRoleCode(), u.getRoleName(), u.isActive(), u.isLocked(), u.getLockSecondsRemaining(),
                u.isMustChangePassword(), u.getLastLoginAt(), u.getPasswordChangedAt(), u.getCreatedAt());
    }

    /** A new account (the password is passed separately, as a char array). */
    public record NewUser(String username, String fullName, String phone, String email, String roleCode,
                          boolean mustChangePassword) {
    }

    /** The editable part of an existing account (the username never changes). */
    public record Changes(int userId, String fullName, String phone, String email, String roleCode, boolean active) {
    }

    /** One line of the permission matrix: who holds the permission. */
    public record PermissionRow(Permission permission, String group, Set<String> roles) {
    }
}

package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Users */
public class User {

    private Integer userId;
    private String username;
    private String passwordHash;
    private String fullName;
    private String phone;
    private String email;
    private Integer roleId;
    private boolean active = true;
    private LocalDateTime lastLoginAt;
    private int failedLoginAttempts;
    private LocalDateTime lockedUntil;
    /** Seconds until the lock expires, computed with the database server clock; 0 if not locked. */
    private long lockSecondsRemaining;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Read-only, joined from Roles
    private String roleCode;
    private String roleName;

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Integer getRoleId() { return roleId; }
    public void setRoleId(Integer roleId) { this.roleId = roleId; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }

    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }

    public long getLockSecondsRemaining() { return lockSecondsRemaining; }
    public void setLockSecondsRemaining(long lockSecondsRemaining) { this.lockSecondsRemaining = lockSecondsRemaining; }

    public boolean isLocked() {
        return lockSecondsRemaining > 0;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public boolean isAdmin() {
        return Role.ADMIN.equals(roleCode);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof User other && userId != null && userId.equals(other.userId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }

    @Override
    public String toString() {
        return fullName;
    }
}

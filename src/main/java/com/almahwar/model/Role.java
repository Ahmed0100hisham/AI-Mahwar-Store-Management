package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Roles */
public class Role {

    public static final String ADMIN = "ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String CASHIER = "CASHIER";
    public static final String STOREKEEPER = "STOREKEEPER";
    public static final String ACCOUNTANT = "ACCOUNTANT";

    private Integer roleId;
    private String roleCode;
    private String roleName;
    private String description;
    private boolean active = true;
    private LocalDateTime createdAt;

    public Integer getRoleId() { return roleId; }
    public void setRoleId(Integer roleId) { this.roleId = roleId; }

    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Role other && roleId != null && roleId.equals(other.roleId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(roleId);
    }

    /** Shown in ComboBox / ListView. */
    @Override
    public String toString() {
        return roleName;
    }
}

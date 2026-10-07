package com.almahwar.api.security;

import com.almahwar.model.Permission;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The authenticated user of the current request, rebuilt from the database on every request (never from token
 * claims). A user who must change the password has <b>no</b> permission at all, exactly like the desktop session.
 */
public record ApiUser(int userId, String username, String fullName, String roleCode, String roleName,
                      boolean mustChangePassword, Set<Permission> permissions, String tokenId) {

    public ApiUser {
        permissions = permissions.isEmpty() ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }

    /** Spring Security authority name of a permission ({@code PERM_PRODUCTS_VIEW}), as used in {@code @PreAuthorize}. */
    public static String authority(Permission permission) {
        return "PERM_" + permission.name();
    }
}

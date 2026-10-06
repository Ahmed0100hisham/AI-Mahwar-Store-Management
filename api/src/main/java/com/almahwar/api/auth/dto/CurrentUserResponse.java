package com.almahwar.api.auth.dto;

import com.almahwar.api.security.ApiUser;
import com.almahwar.api.security.Permission;

import java.util.List;

/**
 * Who is logged in. {@code permissions} lets the app hide what the user cannot do — a convenience only: the server
 * checks every request itself. No hash, salt, lock counter or other security internal is ever included.
 */
public record CurrentUserResponse(int id, String username, String fullName, String roleCode, String roleName,
                                  boolean mustChangePassword, List<String> permissions) {

    public static CurrentUserResponse of(ApiUser user) {
        return new CurrentUserResponse(user.userId(), user.username(), user.fullName(), user.roleCode(),
                user.roleName(), user.mustChangePassword(),
                user.permissions().stream().map(Permission::name).sorted().toList());
    }
}

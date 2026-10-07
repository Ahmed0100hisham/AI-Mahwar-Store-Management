package com.almahwar.api.core;

import com.almahwar.api.security.ApiUser;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;
import com.almahwar.service.RolePermissions;
import com.almahwar.service.SecurityContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Collectors;

/** Request-local principal, already checked against live user state by UserPrincipalLoader. Never caches a user. */
@Component
public final class SpringSecurityContext implements SecurityContext {
    @Override
    public Optional<UserSession> getSession() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof ApiUser principal)) {
            return Optional.empty();
        }
        User user = new User();
        user.setUserId(principal.userId());
        user.setUsername(principal.username());
        user.setFullName(principal.fullName());
        user.setRoleCode(principal.roleCode());
        user.setRoleName(principal.roleName());
        user.setMustChangePassword(principal.mustChangePassword());
        // The principal cannot grant permissions beyond the released role matrix. Preserve any restriction it has.
        var permissions = RolePermissions.forRole(principal.roleCode()).stream()
                .filter(principal::hasPermission).collect(Collectors.toSet());
        // Phase 1 has no server login session or login timestamp. null avoids inventing session state.
        return Optional.of(new UserSession(user, permissions, null, principal.mustChangePassword()));
    }
}

package com.almahwar.api.security;

import com.almahwar.model.Permission;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The authenticated {@link ApiUser} for the service layer, taken from the security context — never from request
 * input — so a service decides with the same identity the security filters verified.
 */
@Component
public class CurrentUser {

    public ApiUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiUser user) {
            return user;
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated API user");
    }

    public boolean has(Permission permission) {
        return require().hasPermission(permission);
    }
}

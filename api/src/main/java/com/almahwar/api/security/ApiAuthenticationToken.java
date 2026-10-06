package com.almahwar.api.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security's view of an {@link ApiUser}: one {@code PERM_*} authority per permission. Holds no token. */
public class ApiAuthenticationToken extends AbstractAuthenticationToken {

    private final ApiUser user;

    public ApiAuthenticationToken(ApiUser user) {
        super(user.permissions().stream().map(p -> new SimpleGrantedAuthority(ApiUser.authority(p))).toList());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public ApiUser getPrincipal() {
        return user;
    }

    /** The bearer token is not kept after authentication. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return user.username();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApiAuthenticationToken other && super.equals(o) && user.equals(other.user);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + user.hashCode();
    }

    /** Authorities are not a secret, but keep logs short. */
    @Override
    public String toString() {
        return "ApiAuthenticationToken[user=" + user.username() + ", role=" + user.roleCode() + "]";
    }
}

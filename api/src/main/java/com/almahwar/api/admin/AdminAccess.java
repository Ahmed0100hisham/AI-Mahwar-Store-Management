package com.almahwar.api.admin;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.model.Permission;
import org.springframework.stereotype.Component;

@Component("adminAccess")
public class AdminAccess {
    private final SpringSecurityContext security;
    public AdminAccess(SpringSecurityContext security) { this.security=security; }
    public boolean allowed(String permission) { return security.hasPermission(Permission.valueOf(permission)); }
    public void require(Permission permission) { security.requirePermission(permission); }
}

package com.almahwar.api.admin;

import com.almahwar.api.manager.ManagerPage;
import com.almahwar.api.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/manager/admin")
@SecurityRequirement(name="bearer")
@Tag(name="Manager administration",description="Released USERS_* permissions (ADMIN only). No permanent deletion. No-store responses. Passwords are supplied in bodies and never returned. Core protects the last active administrator and prevents self-disable/self-role-change.")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service=service; }
    @GetMapping("/users") @PreAuthorize("@adminAccess.allowed('USERS_VIEW')")
    @Operation(summary="Bounded user list",description="page 0..10000, size 1..100 (default 20). Literal username/name q. Assignable role, active filters. Sort name (default), username or created, asc/desc; unique id tie-breaker. No credential or lock state.")
    public PageResponse<AdminDtos.User> users(@ParameterObject @ModelAttribute ManagerPage page,@RequestParam(required=false) String role,@RequestParam(required=false) Boolean active) { return service.users(page,role,active); }
    @GetMapping("/users/{id}") @PreAuthorize("@adminAccess.allowed('USERS_VIEW')")
    public AdminDtos.User detail(@PathVariable int id) { return service.detail(id); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@adminAccess.allowed('USERS_CREATE')")
    @Operation(summary="Create with administrator-supplied temporary password",description="Released username/password/profile rules. Initially active, must change password at login. Password is never returned.")
    public AdminDtos.User create(@Valid @RequestBody AdminDtos.Create request) { return service.create(request); }
    @PutMapping("/users/{id}") @PreAuthorize("@adminAccess.allowed('USERS_EDIT')")
    @Operation(summary="Replace editable profile",description="Full name, phone, email, roleCode and explicit active state. Username immutable. Core self/last-admin rules apply. Sessions require fresh login. A 503 may follow a committed identity change; re-read state before retrying.")
    public AdminDtos.User edit(@PathVariable int id,@Valid @RequestBody AdminDtos.Edit request) { return service.edit(id,request); }
    @PostMapping("/users/{id}/disable") @PreAuthorize("@adminAccess.allowed('USERS_EDIT')")
    @Operation(summary="Disable user",description="Core safety and audit apply. Future access, refresh and login fail. Cleanup failure can return 503 after the identity change commits.")
    public AdminDtos.User disable(@PathVariable int id) { return service.active(id,false); }
    @PostMapping("/users/{id}/enable") @PreAuthorize("@adminAccess.allowed('USERS_EDIT')")
    @Operation(summary="Enable user",description="Old sessions are revoked before enabling. Fresh authentication required. A cleanup failure may return 503 after enabling commits.")
    public AdminDtos.User enable(@PathVariable int id) { return service.active(id,true); }
    @PostMapping("/users/{id}/reset-password") @PreAuthorize("@adminAccess.allowed('USERS_RESET_PASSWORD')")
    @Operation(summary="Reset another user's password",description="Core temporary-password policy, must-change=true, resets login failures and temporary lock. Old access/refresh credentials invalid. No password returned. Own password changes use /auth/change-password. A 503 can follow a committed reset.")
    public AdminDtos.User reset(@PathVariable int id,@Valid @RequestBody AdminDtos.Reset request) { return service.reset(id,request); }
    @GetMapping("/roles") @PreAuthorize("@adminAccess.allowed('USERS_VIEW')")
    public List<AdminDtos.Role> roles() { return service.roles(); }
    @GetMapping("/permissions") @PreAuthorize("@adminAccess.allowed('USERS_VIEW')")
    public List<AdminDtos.Permission> permissions() { return service.permissions(); }
}

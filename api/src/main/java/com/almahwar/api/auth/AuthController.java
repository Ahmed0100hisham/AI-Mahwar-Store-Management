package com.almahwar.api.auth;

import com.almahwar.api.auth.dto.CurrentUserResponse;
import com.almahwar.api.auth.dto.LoginRequest;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.auth.dto.ChangePasswordRequest;
import com.almahwar.api.auth.dto.RefreshRequest;
import com.almahwar.api.error.ApiError;
import com.almahwar.api.security.ApiUser;
import com.almahwar.api.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

/** Authentication endpoints. No SQL here: everything goes through {@link AuthService}. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;
    private final PasswordChangeService passwords;

    public AuthController(AuthService auth, SessionService sessions, PasswordChangeService passwords) {
        this.auth = auth;
        this.sessions = sessions;
        this.passwords = passwords;
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with username and password; returns a short-lived bearer access token",
            description = "Same rules as the desktop login: shared account lock after repeated wrong passwords, "
                    + "one error for unknown user and wrong password, disabled accounts refused.")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "ACCOUNT_DISABLED / NO_PERMISSIONS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "ACCOUNT_LOCKED (Retry-After) / TOO_MANY_REQUESTS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        LoginResponse response = auth.login(request.username(), request.password().toCharArray(), http.getRemoteAddr(),request.deviceLabel());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/me")
    @Operation(summary = "The logged-in user, role and permissions",
            security = @SecurityRequirement(name = "bearer"),
            description = "Available also while a password change is required.")
    @ApiResponse(responseCode = "200", description = "Current user")
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED / SESSION_REVOKED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CurrentUserResponse me(@AuthenticationPrincipal ApiUser user) {
        return CurrentUserResponse.of(user);
    }

    @PostMapping("/refresh")
    @Operation(summary="Rotate an opaque refresh credential")
    public ResponseEntity<LoginResponse> refresh(@RequestBody RefreshRequest request,
                                                HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sessions.refresh(request.refreshToken(),http.getRemoteAddr()));
    }

    @PostMapping("/logout")
    @Operation(summary="Revoke the current device session",security=@SecurityRequirement(name="bearer"))
    public ResponseEntity<Void> logout(@AuthenticationPrincipal ApiUser user, HttpServletRequest http) {
        sessions.logout(user,http.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout-all")
    @Operation(summary="Revoke all of your API sessions, including this one",security=@SecurityRequirement(name="bearer"))
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal ApiUser user, HttpServletRequest http) {
        sessions.logoutAll(user,http.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/sessions")
    @Operation(summary="List your own device sessions",security=@SecurityRequirement(name="bearer"))
    public ResponseEntity<List<SessionService.SessionView>> sessions(
            @AuthenticationPrincipal ApiUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.list(user));
    }

    @DeleteMapping("/sessions/{sid}")
    @Operation(summary="Revoke one of your own sessions",security=@SecurityRequirement(name="bearer"))
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal ApiUser user,
            @PathVariable UUID sid, HttpServletRequest http) {
        sessions.revoke(user,sid,http.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/change-password")
    @Operation(summary="Change your password and sign out all devices",security=@SecurityRequirement(name="bearer"))
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal ApiUser user,
            @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        passwords.change(user,request.currentPassword().toCharArray(),request.newPassword().toCharArray(),
                request.confirmPassword().toCharArray(),http.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

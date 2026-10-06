package com.almahwar.api.auth;

import com.almahwar.api.auth.dto.CurrentUserResponse;
import com.almahwar.api.auth.dto.LoginRequest;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.error.ApiError;
import com.almahwar.api.security.ApiUser;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints. No SQL here: everything goes through {@link AuthService}. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
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
        LoginResponse response = auth.login(request.username(), request.password().toCharArray(), http.getRemoteAddr());
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
}

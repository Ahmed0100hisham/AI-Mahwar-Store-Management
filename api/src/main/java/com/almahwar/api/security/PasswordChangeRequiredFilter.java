package com.almahwar.api.security;

import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * A user with {@code must_change_password} may only see who they are and change the password; every other request is
 * refused with {@code PASSWORD_CHANGE_REQUIRED} (403). Defence in depth: such a user also has no permission at all
 * ({@link UserPrincipalLoader}), as on the desktop, so a forgotten path here still could not reach business data.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** The only paths open before the password is changed ({@code change-password} arrives in API phase 2). */
    static final Set<String> ALLOWED = Set.of("GET /api/v1/auth/me", "POST /api/v1/auth/change-password",
            "POST /api/v1/auth/logout");

    private final ErrorResponseWriter writer;

    public PasswordChangeRequiredFilter(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiUser user && user.mustChangePassword()
                && !ALLOWED.contains(request.getMethod() + " " + pathWithinApplication(request))) {
            writer.write(request, response, ErrorCode.PASSWORD_CHANGE_REQUIRED, null);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        // "/api/v1/auth/me/" or "/api/v1/auth/me;x" must not slip past an exact comparison in either direction
        int semicolon = path.indexOf(';');
        return semicolon >= 0 ? path.substring(0, semicolon) : path;
    }
}

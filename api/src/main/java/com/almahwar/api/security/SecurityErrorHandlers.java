package com.almahwar.api.security;

import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.error.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 / 403 / 503 answers of the security filters, in the API's error format. The reason a token was refused is
 * logged at debug level only; the client gets a stable code and nothing about the token or the account.
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityErrorHandlers.class);

    private final ErrorResponseWriter writer;

    public SecurityErrorHandlers(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        if (e instanceof AuthenticationServiceException || e instanceof UserPrincipalLoader.UserStateUnavailableException) {
            LOG.error("Authentication dependency unavailable [{} {}]", request.getMethod(), request.getRequestURI());
            writer.write(request, response, ErrorCode.SERVICE_UNAVAILABLE, null);
            return;
        }
        LOG.debug("Unauthenticated request [{} {}]: {}", request.getMethod(), request.getRequestURI(),
                e.getClass().getSimpleName());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ErrorCode code = e instanceof UserPrincipalLoader.SessionRevokedException
                ? ErrorCode.SESSION_REVOKED : ErrorCode.UNAUTHORIZED;
        writer.write(request, response, code, null);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        writer.write(request, response, ErrorCode.FORBIDDEN, null);
    }
}

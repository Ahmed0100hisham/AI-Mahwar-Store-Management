package com.almahwar.api.error;

import com.almahwar.api.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes an {@link ApiError} from places outside Spring MVC (security filters). */
@Component
public class ErrorResponseWriter {

    private final JsonMapper json;

    public ErrorResponseWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ApiError body = ApiError.of(code, message, request.getRequestURI(), RequestIdFilter.current(request), null);
        response.setStatus(code.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }
}

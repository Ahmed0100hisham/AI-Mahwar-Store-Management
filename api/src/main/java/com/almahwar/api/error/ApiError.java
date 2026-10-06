package com.almahwar.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The one JSON error body of the API. Never contains a stack trace, SQL, a database message, a file path or a secret:
 * the details stay in the server log under {@link #requestId()}, which the client can quote to support.
 *
 * <pre>
 * { "timestamp": "2026-10-07T10:15:30Z", "status": 400, "code": "VALIDATION_ERROR",
 *   "message": "البيانات المرسلة غير صحيحة.", "path": "/api/v1/products", "requestId": "…",
 *   "fieldErrors": [ { "field": "size", "message": "…" } ] }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(Instant timestamp, int status, String code, String message, String path, String requestId,
                       List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String message, String path, String requestId, List<FieldError> fields) {
        return new ApiError(Instant.now(), code.status().value(), code.name(),
                message == null ? code.defaultMessage() : message, path, requestId, fields == null ? List.of() : fields);
    }
}

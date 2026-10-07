package com.almahwar.api.error;

import com.almahwar.api.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns every exception into an {@link ApiError}. Error types are logged with the request id, without driver text
 * or credential-bearing exception details, and answered generically: no SQL, class name or stack trace ever
 * reaches the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().status());
        if (e.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(error(e.code(), e.getMessage(), request, null));
    }

    // ---------- validation ----------

    @ExceptionHandler(FieldValidationException.class)
    ResponseEntity<ApiError> field(FieldValidationException e, HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_ERROR, null, request,
                List.of(new ApiError.FieldError(e.field(), e.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return respond(ErrorCode.VALIDATION_ERROR, null, request, fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameters(HandlerMethodValidationException e, HttpServletRequest request) {
        List<ApiError.FieldError> fields = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(err ->
                fields.add(new ApiError.FieldError(result.getMethodParameter().getParameterName(),
                        err.getDefaultMessage()))));
        return respond(ErrorCode.VALIDATION_ERROR, null, request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException e, HttpServletRequest request) {
        List<ApiError.FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage())).toList();
        return respond(ErrorCode.VALIDATION_ERROR, null, request, fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_ERROR, null, request,
                List.of(new ApiError.FieldError(e.getName(), "قيمة غير صالحة.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_ERROR, null, request,
                List.of(new ApiError.FieldError(e.getParameterName(), "مطلوب.")));
    }

    /** Malformed JSON: the parser's message (which may quote the input) is not returned. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, null, request, null);
    }

    // ---------- HTTP ----------

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiError> notFound(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, null, request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, null, request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, request, null);
    }

    // ---------- security (method-level checks in the service layer) ----------

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class,
            com.almahwar.service.AccessDeniedException.class})
    ResponseEntity<ApiError> denied(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, null, request, null);
    }

    // ---------- database / unexpected ----------

    /** Includes a transaction that cannot begin because no connection can be obtained. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception e, HttpServletRequest request) {
        LOG.error("Database unavailable [{} {}] ({})", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
        return respond(ErrorCode.SERVICE_UNAVAILABLE, null, request, null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException e, HttpServletRequest request) {
        LOG.error("Database error [{} {}] ({})", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
        return respond(ErrorCode.INTERNAL_ERROR, null, request, null);
    }

    @ExceptionHandler(com.almahwar.dao.DataAccessException.class)
    ResponseEntity<ApiError> coreDatabase(com.almahwar.dao.DataAccessException e, HttpServletRequest request) {
        LOG.error("Shared core database error [{} {}] ({})", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
        return respond(ErrorCode.INTERNAL_ERROR, null, request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("Unexpected error [{} {}] ({})", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
        return respond(ErrorCode.INTERNAL_ERROR, null, request, null);
    }

    // ---------- helpers ----------

    private static ResponseEntity<ApiError> respond(ErrorCode code, String message, HttpServletRequest request,
                                                    List<ApiError.FieldError> fields) {
        return ResponseEntity.status(code.status()).body(error(code, message, request, fields));
    }

    private static ApiError error(ErrorCode code, String message, HttpServletRequest request,
                                  List<ApiError.FieldError> fields) {
        return ApiError.of(code, message, request.getRequestURI(), RequestIdFilter.current(request), fields);
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}

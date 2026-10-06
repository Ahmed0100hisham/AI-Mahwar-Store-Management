package com.almahwar.api.error;

/**
 * A failure the client should see with a specific {@link ErrorCode}. The message is shown to the user (Arabic) and
 * must never contain internal details.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Long retryAfterSeconds) {
        super(message, null, false, false);   // no stack trace: an expected outcome, not a bug
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    /** For {@code Retry-After} (locks and throttling); {@code null} when not applicable. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

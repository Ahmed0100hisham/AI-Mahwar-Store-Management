package com.almahwar.api.error;

/** A request value the service refuses (e.g. an unknown sort field): answered as VALIDATION_ERROR on that field. */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message, null, false, false);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

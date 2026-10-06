package com.almahwar.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input was rejected by a service. Carries one Arabic message per field
 * (in form order) so a screen can show each message next to its field, and a
 * future REST API can return them as a 400/422 body.
 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super(String.join("\n", errors.values()));
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    /** Field name → Arabic message. */
    public Map<String, String> getErrors() {
        return errors;
    }

    public String errorFor(String field) {
        return errors.get(field);
    }
}

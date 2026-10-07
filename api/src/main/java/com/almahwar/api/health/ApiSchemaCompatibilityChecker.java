package com.almahwar.api.health;

import com.almahwar.api.session.ApiSessionSchemaRepository;
import org.springframework.stereotype.Component;

/** Safe readiness service boundary: HTTP controllers do not access session persistence directly. */
@Component
public class ApiSchemaCompatibilityChecker {
    private final ApiSessionSchemaRepository schema;

    public ApiSchemaCompatibilityChecker(ApiSessionSchemaRepository schema) {
        this.schema = schema;
    }

    public boolean compatible() {
        return schema.compatible();
    }
}

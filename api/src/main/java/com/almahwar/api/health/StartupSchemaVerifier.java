package com.almahwar.api.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the API on a database it is not compatible with (see {@link SchemaCompatibilityChecker}). Runs
 * after all beans exist and <b>before</b> the web server opens its port, so no request is ever served against a wrong
 * schema. The failure names the problem in the log (version, missing tables) but nothing secret.
 */
@Component
public class StartupSchemaVerifier implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(StartupSchemaVerifier.class);

    private final SchemaCompatibilityChecker checker;

    public StartupSchemaVerifier(SchemaCompatibilityChecker checker) {
        this.checker = checker;
    }

    @Override
    public void afterSingletonsInstantiated() {
        SchemaCompatibilityChecker.Result result = checker.check();
        if (!result.compatible()) {
            throw new IllegalStateException("Database is not compatible with this API (" + result.status() + "): "
                    + result.detail() + ". Nothing was changed in the database.");
        }
        LOG.info("Database compatible: {}", result.detail());
    }
}

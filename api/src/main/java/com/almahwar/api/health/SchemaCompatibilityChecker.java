package com.almahwar.api.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;

/**
 * Is the configured database one this API can safely work on? Connection works, the database exists,
 * {@code Schema_Info} exists, the schema version is <b>exactly</b> {@value #REQUIRED_SCHEMA_VERSION} (the frozen
 * desktop 1.0.0 schema), and every table the implemented endpoints use exists.
 * <p>
 * Exact match on purpose: an older schema lacks columns, and a newer one may have changed rules the API does not know.
 * Nothing is ever created or migrated here.
 */
@Component
public class SchemaCompatibilityChecker {

    public static final String REQUIRED_SCHEMA_VERSION = com.almahwar.service.SettingsService.REQUIRED_SCHEMA_VERSION;

    /** Tables used by the endpoints implemented so far (grows with each API phase). */
    public static final List<String> REQUIRED_TABLES = List.of("Schema_Info", "Users", "Roles", "Audit_Log",
            "Products", "Categories", "Units", "Brands");

    public enum Status { COMPATIBLE, UNREACHABLE, DATABASE_UNAVAILABLE, SCHEMA_MISSING, VERSION_MISMATCH, TABLES_MISSING }

    /** {@code detail} is for the server log only — never sent to a client. */
    public record Result(Status status, String detail) {
        public boolean compatible() {
            return status == Status.COMPATIBLE;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SchemaCompatibilityChecker.class);

    private final DatabaseStatusRepository database;

    public SchemaCompatibilityChecker(DatabaseStatusRepository database) {
        this.database = database;
    }

    public Result check() {
        try {
            String version = database.schemaVersion().orElse(null);
            if (version == null) {
                return new Result(Status.SCHEMA_MISSING, "Schema_Info table or row is missing");
            }
            if (!REQUIRED_SCHEMA_VERSION.equals(version.trim())) {
                return new Result(Status.VERSION_MISMATCH,
                        "schema version is " + version + ", this API requires exactly " + REQUIRED_SCHEMA_VERSION);
            }
            List<String> missing = database.missingTables(REQUIRED_TABLES);
            if (!missing.isEmpty()) {
                return new Result(Status.TABLES_MISSING, "missing tables: " + String.join(", ", missing));
            }
            return new Result(Status.COMPATIBLE, "schema " + version);
        } catch (DataAccessException e) {
            LOG.warn("Database check failed ({})", e.getClass().getSimpleName());
            // 4060: cannot open the database (missing, or the login has no access to it); 916: no access
            Integer code = sqlErrorCode(e);
            if (code != null && (code == 4060 || code == 916)) {
                return new Result(Status.DATABASE_UNAVAILABLE, "database missing or not accessible (SQL error " + code + ")");
            }
            return new Result(Status.UNREACHABLE, "cannot connect to the database: " + e.getClass().getSimpleName());
        }
    }

    private static Integer sqlErrorCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() != 0) {
                return sql.getErrorCode();
            }
        }
        return null;
    }
}

package com.almahwar.model;

import java.util.List;

/**
 * The outcome of the startup health check of the database (read only — nothing is ever repaired by it).
 *
 * @param message        what is wrong, in Arabic, safe to show (no user name, password, JDBC URL or SQL text)
 * @param schemaVersion  {@code Schema_Info} of the database, if it could be read
 * @param requiredSchema the schema this program needs
 * @param usersExist     the database has at least one user (otherwise the first-administrator setup is offered)
 * @param details        more specific findings (e.g. the missing settings or tables), Arabic
 */
public record DatabaseHealth(Status status, String message, String schemaVersion, String requiredSchema,
                             boolean usersExist, List<String> details) {

    public enum Status {
        HEALTHY(false),
        /** Users exist but none is an active administrator: the program works, user administration does not. */
        NO_ACTIVE_ADMIN(false),
        /** A required setting is missing or invalid (also: an untrusted TLS certificate). */
        CONFIGURATION_ERROR(true),
        SERVER_UNREACHABLE(true),
        /** SQL Server refused the configured login. */
        LOGIN_FAILED(true),
        /** The database does not exist, is not ONLINE, or the login has no access to it. */
        DATABASE_UNAVAILABLE(true),
        SCHEMA_MISSING(true),
        /** The database is older than the program: run the upgrade script. */
        SCHEMA_OUTDATED(true),
        /** The database was upgraded for a newer program: update the program. */
        SCHEMA_TOO_NEW(true),
        CRITICAL_TABLE_MISSING(true);

        private final boolean blocking;

        Status(boolean blocking) {
            this.blocking = blocking;
        }

        /** The program must not be used against this database. */
        public boolean blocking() {
            return blocking;
        }
    }

    public boolean canStart() {
        return !status.blocking();
    }
}

package com.almahwar.api.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes to the desktop's {@code dbo.Audit_Log}, with the same action codes ({@code LOGIN}, {@code LOGIN_FAILED},
 * {@code ACCOUNT_LOCKED}, …) so the desktop's audit screens and reports show API activity too. The machine column
 * says {@code API/<client address>}, which tells API entries apart from desktop ones.
 */
@Repository
public class AuditLogRepository {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogRepository.class);
    private static final int MACHINE_MAX = 100;   // Audit_Log.machine_name NVARCHAR(100)

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Audit failures are logged but never block authentication (the desktop does the same). Must not be called inside
     * a transaction that may roll back the entry it documents.
     */
    public void logQuietly(Integer userId, String action, String table, String recordId, String description,
                           String clientAddress) {
        try {
            jdbc.sql("""
                            INSERT INTO dbo.Audit_Log (user_id, action, table_name, record_id, description, machine_name)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)
                    .params(userId, action, table, recordId, description, machine(clientAddress))
                    .update();
        } catch (RuntimeException e) {
            LOG.warn("Could not write audit log entry {} ({})", action, e.getClass().getSimpleName());
        }
    }

    static String machine(String clientAddress) {
        String m = "API/" + (clientAddress == null ? "unknown" : clientAddress);
        return m.length() > MACHINE_MAX ? m.substring(0, MACHINE_MAX) : m;
    }
}

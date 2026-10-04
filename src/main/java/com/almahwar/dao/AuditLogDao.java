package com.almahwar.dao;

/**
 * Writes to {@code Audit_Log}.
 */
public class AuditLogDao extends BaseDao {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String INSERT = "INSERT";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";

    private static final String MACHINE_NAME = resolveMachineName();

    /**
     * @param userId      acting user, or {@code null} (e.g. failed login with an unknown username)
     * @param tableName   affected table, or {@code null} for login/logout
     * @param recordId    affected row id, or {@code null}
     */
    public void log(Integer userId, String action, String tableName, String recordId, String description) {
        update("""
                INSERT INTO dbo.Audit_Log (user_id, action, table_name, record_id, description, machine_name)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, action, tableName, recordId, description, MACHINE_NAME);
    }

    private static String resolveMachineName() {
        String name = System.getenv("COMPUTERNAME");   // Windows
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        return name;
    }
}

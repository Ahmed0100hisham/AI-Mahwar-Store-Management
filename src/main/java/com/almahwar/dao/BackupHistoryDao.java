package com.almahwar.dao;

import com.almahwar.config.DatabaseConnection;
import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupInfo.Kind;
import com.almahwar.model.BackupInfo.Status;
import com.almahwar.model.BackupInfo.Verification;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@code Backup_History} and the backup / restore audit entries, in the database being backed up (the program's
 * database, or a temporary test database) — every method opens its own connection to that database.
 */
public class BackupHistoryDao extends BaseDao {

    public static final String TABLE = "Backup_History";

    private final String databaseName;
    private final AuditLogDao auditLogDao;

    private static final String SELECT = """
            SELECT backup_id, file_name, server_directory, database_name, backup_kind, status, verify_status,
                   schema_version, size_bytes, created_at, created_by_name, completed_at, verified_at,
                   verified_by_name, note
            FROM dbo.Backup_History
            """;

    public BackupHistoryDao(String databaseName, AuditLogDao auditLogDao) {
        this.databaseName = databaseName;
        this.auditLogDao = auditLogDao;
    }

    private Connection open() throws SQLException {
        return DatabaseConnection.getConnection(databaseName);
    }

    private static BackupInfo map(ResultSet rs) throws SQLException {
        long size = rs.getLong("size_bytes");
        return new BackupInfo(rs.getInt("backup_id"), rs.getString("file_name"), rs.getString("server_directory"),
                rs.getString("database_name"), Kind.valueOf(rs.getString("backup_kind")),
                Status.valueOf(rs.getString("status")), Verification.valueOf(rs.getString("verify_status")),
                rs.getString("schema_version"), rs.wasNull() ? null : size, getDateTime(rs, "created_at"),
                rs.getString("created_by_name"), getDateTime(rs, "completed_at"), getDateTime(rs, "verified_at"),
                rs.getString("verified_by_name"), rs.getString("note"));
    }

    /** Newest first. */
    public List<BackupInfo> findAll() {
        try (Connection con = open()) {
            return queryList(con, SELECT + " ORDER BY created_at DESC, backup_id DESC", BackupHistoryDao::map);
        } catch (SQLException e) {
            throw new DataAccessException("Backup history failed", e);
        }
    }

    public Optional<BackupInfo> findById(int backupId) {
        try (Connection con = open()) {
            return queryOne(con, SELECT + " WHERE backup_id = ?", BackupHistoryDao::map, backupId);
        } catch (SQLException e) {
            throw new DataAccessException("Backup history failed", e);
        }
    }

    /**
     * Reserves a file name with a CREATING row (the UNIQUE file name makes two PCs never pick the same one).
     *
     * @throws DataAccessException with {@link DataAccessException#isDuplicateKey()} when the name is taken
     */
    public int insertCreating(String fileName, String serverDirectory, Kind kind, String schemaVersion,
                              Integer userId, String userName) {
        try (Connection con = open()) {
            return insert(con, """
                    INSERT INTO dbo.Backup_History (file_name, server_directory, database_name, backup_kind, status,
                                                    verify_status, schema_version, created_by, created_by_name)
                    VALUES (?, ?, ?, ?, 'CREATING', 'NOT_VERIFIED', ?, ?, ?)
                    """, fileName, serverDirectory, databaseName, kind.name(), schemaVersion,
                    knownUser(con, userId), userName);
        } catch (SQLException e) {
            throw new DataAccessException("Backup reservation failed", e);
        }
    }

    /** CREATING → COMPLETED (only from CREATING). */
    public void markCompleted(int backupId, Long sizeBytes) {
        changeStatus(backupId, Status.COMPLETED, sizeBytes, null);
    }

    /** CREATING → FAILED with a short Arabic reason (only from CREATING). */
    public void markFailed(int backupId, String note) {
        changeStatus(backupId, Status.FAILED, null, note);
    }

    private void changeStatus(int backupId, Status status, Long sizeBytes, String note) {
        try (Connection con = open()) {
            update(con, """
                    UPDATE dbo.Backup_History
                    SET status = ?, size_bytes = COALESCE(?, size_bytes), note = ?, completed_at = SYSDATETIME()
                    WHERE backup_id = ? AND status = 'CREATING'
                    """, status.name(), sizeBytes, truncate(note), backupId);
        } catch (SQLException e) {
            throw new DataAccessException("Backup status failed", e);
        }
    }

    /** The result of the latest verification (only for a COMPLETED backup). */
    public void markVerification(int backupId, Verification verification, String userName, String note) {
        try (Connection con = open()) {
            update(con, """
                    UPDATE dbo.Backup_History
                    SET verify_status = ?, verified_at = SYSDATETIME(), verified_by_name = ?, note = ?
                    WHERE backup_id = ? AND status = 'COMPLETED'
                    """, verification.name(), userName, truncate(note), backupId);
        } catch (SQLException e) {
            throw new DataAccessException("Backup verification status failed", e);
        }
    }

    /**
     * After a restore the table is as old as the restored backup: brings back the rows the program knew just
     * before (backups made later, the safety backup) and their latest status — by file name, nothing is deleted.
     */
    public void merge(List<BackupInfo> known) {
        try (Connection con = open()) {
            for (BackupInfo b : known) {
                int changed = update(con, """
                        UPDATE dbo.Backup_History
                        SET status = ?, verify_status = ?, size_bytes = ?, completed_at = ?, verified_at = ?,
                            verified_by_name = ?, note = ?
                        WHERE file_name = ?
                        """, b.status().name(), b.verification().name(), b.sizeBytes(), b.completedAt(),
                        b.verifiedAt(), b.verifiedByName(), truncate(b.note()), b.fileName());
                if (changed == 0) {
                    update(con, """
                            INSERT INTO dbo.Backup_History (file_name, server_directory, database_name, backup_kind,
                                    status, verify_status, schema_version, size_bytes, created_at, created_by_name,
                                    completed_at, verified_at, verified_by_name, note)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, b.fileName(), b.serverDirectory(), b.databaseName(), b.kind().name(),
                            b.status().name(), b.verification().name(), b.schemaVersion(), b.sizeBytes(),
                            b.createdAt(), b.createdByName(), b.completedAt(), b.verifiedAt(), b.verifiedByName(),
                            truncate(b.note()));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Backup history merge failed", e);
        }
    }

    /**
     * An audit entry in this database. The user is linked only if the account exists here (after a restore it
     * may not); the description then still names them.
     */
    public void audit(Integer userId, String action, Integer backupId, String description) {
        try (Connection con = open()) {
            auditLogDao.log(con, knownUser(con, userId), action, TABLE, backupId == null ? null : String.valueOf(backupId),
                    truncate(description));
        } catch (SQLException e) {
            throw new DataAccessException("Backup audit failed", e);
        }
    }

    private Integer knownUser(Connection con, Integer userId) {
        if (userId == null) {
            return null;
        }
        return queryOne(con, "SELECT user_id FROM dbo.Users WHERE user_id = ?", rs -> rs.getInt(1), userId).orElse(null);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 400 ? text : text.substring(0, 399) + "…";
    }
}

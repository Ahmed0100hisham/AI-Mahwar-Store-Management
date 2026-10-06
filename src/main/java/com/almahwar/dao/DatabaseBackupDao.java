package com.almahwar.dao;

import com.almahwar.config.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQL Server backup / restore of one database (infrastructure, no business rules).
 * <p>
 * Everything runs on a connection to {@code master}: SQL Server reads and writes the backup files itself, on its
 * own machine, so every path here is a path <b>as SQL Server sees it</b>; and a restore must never run from a
 * connection that is inside the database being replaced. The database name, paths and logical file names are
 * always statement parameters (T-SQL variables) — never text concatenated into SQL. The only dynamic SQL is
 * {@code ALTER DATABASE}, which takes no variable: there the name goes through {@code QUOTENAME} on the server.
 */
public class DatabaseBackupDao {

    /** The program's database (or a temporary test database), as a plain identifier. */
    private final String databaseName;

    /** One backup set in a file ({@code RESTORE HEADERONLY}). */
    public record BackupHeader(int position, String databaseName, int backupType, boolean copyOnly,
                               boolean hasChecksums, long sizeBytes, LocalDateTime finishedAt) {
        /** Backup type 1 = full database backup. */
        public boolean fullDatabase() {
            return backupType == 1;
        }
    }

    /** A file of the database: logical name and where it lives on the server. */
    public record DatabaseFile(String logicalName, String physicalName) {
    }

    /** {@code sys.databases}: e.g. ONLINE / RESTORING / RECOVERY_PENDING, MULTI_USER / SINGLE_USER. */
    public record DatabaseState(String state, String userAccess) {
        public boolean online() {
            return "ONLINE".equals(state);
        }
    }

    /** Read-only facts about a database after a restore. */
    public record Health(String schemaVersion, List<String> missingTables, int activeAdmins,
                         Map<String, Long> consistency) {
    }

    public DatabaseBackupDao(String databaseName) {
        if (databaseName == null || !databaseName.matches("[A-Za-z0-9_]{1,100}")) {
            throw new IllegalArgumentException("Invalid database name");
        }
        this.databaseName = databaseName;
    }

    public String databaseName() {
        return databaseName;
    }

    private static Connection master() throws SQLException {
        return DatabaseConnection.getConnection("master");
    }

    // ---------- server facts ----------

    /** SQL Server's clock (the same for every PC). */
    public LocalDateTime serverTime() {
        try (Connection con = master();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(SYSDATETIME() AS DATETIME2(0))")) {
            rs.next();
            return rs.getObject(1, LocalDateTime.class);
        } catch (SQLException e) {
            throw new DataAccessException("Server time failed", e);
        }
    }

    /** SQL Server's own default backup folder (SQL Server 2019+), if it reports one. */
    public Optional<String> serverDefaultDirectory() {
        try (Connection con = master();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS NVARCHAR(400))")) {
            rs.next();
            String path = rs.getString(1);
            return path == null || path.isBlank() ? Optional.empty() : Optional.of(path);
        } catch (SQLException e) {
            throw new DataAccessException("Default backup directory failed", e);
        }
    }

    /** {@code Schema_Info} of the database, if the table exists. */
    public Optional<String> schemaVersion() {
        try (Connection con = DatabaseConnection.getConnection(databaseName)) {
            return schemaVersion(con);
        } catch (SQLException e) {
            throw new DataAccessException("Schema version failed", e);
        }
    }

    private static Optional<String> schemaVersion(Connection con) throws SQLException {
        return Optional.ofNullable(DatabaseHealthDao.schemaVersion(con));
    }

    public DatabaseState state() {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT state_desc, user_access_desc FROM sys.databases WHERE name = ?")) {
            ps.setNString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new DatabaseState(rs.getString(1), rs.getString(2)) : new DatabaseState("MISSING", null);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Database state failed", e);
        }
    }

    // ---------- operation lock (all PCs) ----------

    /**
     * Takes SQL Server's exclusive application lock for backup / restore of this database, without waiting, on a
     * dedicated {@code master} connection: held until the returned handle is closed (or the connection drops), and
     * seen by every PC — two backups, two restores or a backup during a restore can never overlap.
     *
     * @return the lock, or empty if another backup / restore holds it
     */
    public Optional<AutoCloseable> tryLock() {
        Connection con = null;
        try {
            con = master();
            try (PreparedStatement ps = con.prepareStatement("DECLARE @r INT; EXEC @r = sp_getapplock @Resource = ?, "
                    + "@LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = 0; SELECT @r;")) {
                ps.setNString(1, "AlMahwar.backup." + databaseName);
                int result = firstInt(ps);
                if (result < 0) {
                    con.close();
                    return Optional.empty();
                }
            }
            Connection held = con;
            return Optional.of(held::close);   // closing the session releases the lock
        } catch (SQLException e) {
            closeQuietly(con);
            throw new DataAccessException("Backup lock failed", e);
        }
    }

    private static int firstInt(PreparedStatement ps) throws SQLException {
        boolean hasResult = ps.execute();
        while (true) {
            if (hasResult) {
                try (ResultSet rs = ps.getResultSet()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } else if (ps.getUpdateCount() == -1) {
                throw new SQLException("No result");
            }
            hasResult = ps.getMoreResults();
        }
    }

    private static void closeQuietly(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException ignored) {
                // nothing to do
            }
        }
    }

    // ---------- backup ----------

    /**
     * Full, copy-only backup with page checksums to {@code serverPath} (as SQL Server sees it). Copy-only keeps any
     * backup plan of the server's administrator (differential / log backups) intact. NOINIT: an existing file is
     * never overwritten — the caller makes sure the name is new and checks the file afterwards.
     */
    public void backup(String serverPath, String backupSetName) {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement("BACKUP DATABASE ? TO DISK = ? "
                     + "WITH COPY_ONLY, CHECKSUM, NOINIT, NOSKIP, NAME = ?")) {
            ps.setQueryTimeout(0);
            ps.setNString(1, databaseName);
            ps.setNString(2, serverPath);
            ps.setNString(3, backupSetName);
            drain(ps);
        } catch (SQLException e) {
            throw new DataAccessException("Backup failed", e);
        }
    }

    /**
     * The backup sets in a file ({@code RESTORE HEADERONLY}); empty when SQL Server cannot open the file (it does
     * not exist, or the folder is missing / not accessible).
     *
     * @throws DataAccessException when the file exists but is not a readable backup
     */
    public Optional<List<BackupHeader>> headers(String serverPath) {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement("RESTORE HEADERONLY FROM DISK = ?")) {
            ps.setQueryTimeout(0);
            ps.setNString(1, serverPath);
            List<BackupHeader> sets = new ArrayList<>();
            boolean hasResult = ps.execute();
            while (hasResult || ps.getUpdateCount() != -1) {
                if (hasResult) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            sets.add(new BackupHeader(rs.getInt("Position"), rs.getString("DatabaseName"),
                                    rs.getInt("BackupType"), rs.getBoolean("IsCopyOnly"),
                                    rs.getBoolean("HasBackupChecksums"), rs.getLong("BackupSize"),
                                    rs.getObject("BackupFinishDate", LocalDateTime.class)));
                        }
                    }
                }
                hasResult = ps.getMoreResults();
            }
            return Optional.of(sets);
        } catch (SQLException e) {
            if (e.getErrorCode() == 3201) {   // cannot open backup device: no such file / folder
                return Optional.empty();
            }
            throw new DataAccessException("Backup header failed", e);
        }
    }

    /** {@code RESTORE VERIFYONLY} of the first backup set: SQL Server reads the whole file (and its checksums). */
    public void verifyOnly(String serverPath, boolean withChecksum) {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement("RESTORE VERIFYONLY FROM DISK = ? WITH FILE = 1"
                     + (withChecksum ? ", CHECKSUM" : ""))) {
            ps.setQueryTimeout(0);
            ps.setNString(1, serverPath);
            drain(ps);
        } catch (SQLException e) {
            throw new DataAccessException("Verify failed", e);
        }
    }

    // ---------- restore ----------

    /** Logical and physical files inside a backup ({@code RESTORE FILELISTONLY}). */
    public List<DatabaseFile> filesInBackup(String serverPath) {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement("RESTORE FILELISTONLY FROM DISK = ? WITH FILE = 1")) {
            ps.setQueryTimeout(0);
            ps.setNString(1, serverPath);
            List<DatabaseFile> files = new ArrayList<>();
            boolean hasResult = ps.execute();
            while (hasResult || ps.getUpdateCount() != -1) {
                if (hasResult) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            files.add(new DatabaseFile(rs.getString("LogicalName"), rs.getString("PhysicalName")));
                        }
                    }
                }
                hasResult = ps.getMoreResults();
            }
            return files;
        } catch (SQLException e) {
            throw new DataAccessException("File list failed", e);
        }
    }

    /** The database's current files (logical name → where it lives now). */
    public List<DatabaseFile> currentFiles() {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT name, physical_name FROM sys.master_files WHERE database_id = DB_ID(?) ORDER BY file_id")) {
            ps.setNString(1, databaseName);
            List<DatabaseFile> files = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.add(new DatabaseFile(rs.getString(1), rs.getString(2)));
                }
            }
            return files;
        } catch (SQLException e) {
            throw new DataAccessException("Database files failed", e);
        }
    }

    /**
     * Replaces the database with the first backup set of {@code serverPath}, from {@code master}: other
     * connections are disconnected (SINGLE_USER WITH ROLLBACK IMMEDIATE, in the same batch so nobody slips in),
     * the files are restored over the database's current files ({@code moves}: logical name → current physical
     * file), with checksums, and the database is recovered (ONLINE). Multi-user access is always switched back
     * afterwards, also when the restore fails.
     */
    public void restore(String serverPath, List<DatabaseFile> moves) {
        StringBuilder sql = new StringBuilder("""
                DECLARE @db SYSNAME = ?;
                DECLARE @single NVARCHAR(400) = N'ALTER DATABASE ' + QUOTENAME(@db) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE';
                EXEC (@single);
                RESTORE DATABASE @db FROM DISK = ? WITH FILE = 1, REPLACE, RECOVERY, CHECKSUM""");
        for (int i = 0; i < moves.size(); i++) {
            sql.append(", MOVE ? TO ?");
        }
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(0);
            int i = 1;
            ps.setNString(i++, databaseName);
            ps.setNString(i++, serverPath);
            for (DatabaseFile f : moves) {
                ps.setNString(i++, f.logicalName());
                ps.setNString(i++, f.physicalName());
            }
            drain(ps);
        } catch (SQLException e) {
            throw new DataAccessException("Restore failed", e);
        } finally {
            ensureMultiUser();
        }
    }

    /** Switches the database back to MULTI_USER if it is online and was left otherwise (best effort). */
    public void ensureMultiUser() {
        try (Connection con = master();
             PreparedStatement ps = con.prepareStatement("""
                     DECLARE @db SYSNAME = ?;
                     IF DB_ID(@db) IS NOT NULL AND DATABASEPROPERTYEX(@db, 'Status') = 'ONLINE'
                        AND DATABASEPROPERTYEX(@db, 'UserAccess') <> 'MULTI_USER'
                     BEGIN
                         DECLARE @multi NVARCHAR(400) = N'ALTER DATABASE ' + QUOTENAME(@db) + N' SET MULTI_USER';
                         EXEC (@multi);
                     END""")) {
            ps.setNString(1, databaseName);
            drain(ps);
        } catch (SQLException e) {
            java.util.logging.Logger.getLogger(DatabaseBackupDao.class.getName())
                    .warning("Could not switch the database back to MULTI_USER: " + e.getMessage());
        }
    }

    // ---------- health (read only) ----------

    private static final List<String> CRITICAL_TABLES = DatabaseHealthDao.CRITICAL_TABLES;

    private static final Map<String, String> CONSISTENCY = new LinkedHashMap<>();

    static {
        CONSISTENCY.put("stock", "SELECT COUNT(*) FROM dbo.Products p WHERE p.quantity <> COALESCE("
                + "(SELECT SUM(quantity) FROM dbo.Stock_Movements m WHERE m.product_id = p.product_id), 0)");
        CONSISTENCY.put("negativeStock", "SELECT COUNT(*) FROM dbo.Products WHERE quantity < 0");
        CONSISTENCY.put("customers", "SELECT COUNT(*) FROM dbo.Customers c WHERE c.balance <> COALESCE("
                + "(SELECT SUM(debit - credit) FROM dbo.Account_Ledger l WHERE l.customer_id = c.customer_id), 0)");
        CONSISTENCY.put("suppliers", "SELECT COUNT(*) FROM dbo.Suppliers s WHERE s.balance <> COALESCE("
                + "(SELECT SUM(credit - debit) FROM dbo.Account_Ledger l WHERE l.supplier_id = s.supplier_id), 0)");
    }

    /**
     * Opens a fresh connection to the database and reads: schema version, missing critical tables, active
     * administrators and a few read-only consistency counts (only when the tables exist). Nothing is changed.
     */
    public Health health() {
        try (Connection con = DatabaseConnection.getConnection(databaseName)) {
            String schema = schemaVersion(con).orElse(null);
            List<String> missing = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT OBJECT_ID(?, N'U')")) {
                for (String table : CRITICAL_TABLES) {
                    ps.setNString(1, "dbo." + table);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getObject(1) == null) {
                            missing.add(table);
                        }
                    }
                }
            }
            int admins = 0;
            Map<String, Long> counts = new LinkedHashMap<>();
            if (missing.isEmpty()) {
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dbo.Users u JOIN dbo.Roles r "
                             + "ON r.role_id = u.role_id WHERE r.role_code = 'ADMIN' AND u.is_active = 1")) {
                    rs.next();
                    admins = rs.getInt(1);
                }
                for (Map.Entry<String, String> check : CONSISTENCY.entrySet()) {
                    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(check.getValue())) {
                        rs.next();
                        counts.put(check.getKey(), rs.getLong(1));
                    }
                }
            }
            return new Health(schema, missing, admins, counts);
        } catch (SQLException e) {
            throw new DataAccessException("Health check failed", e);
        }
    }

    // ---------- helpers ----------

    /** Runs a statement and reads through all its results, so SQL Server errors raised late are not missed. */
    private static void drain(PreparedStatement ps) throws SQLException {
        boolean hasResult = ps.execute();
        while (hasResult || ps.getUpdateCount() != -1) {
            if (hasResult) {
                try (ResultSet rs = ps.getResultSet()) {
                    while (rs.next()) {
                        // ignore
                    }
                }
            }
            hasResult = ps.getMoreResults();
        }
    }
}

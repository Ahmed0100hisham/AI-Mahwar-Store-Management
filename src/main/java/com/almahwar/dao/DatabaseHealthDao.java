package com.almahwar.dao;

import com.almahwar.config.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only facts for the startup health check. Never writes, repairs or migrates anything.
 */
public class DatabaseHealthDao {

    /** Tables the program cannot work without. */
    public static final List<String> CRITICAL_TABLES = List.of("Users", "Roles", "Products", "Stock_Movements",
            "Sales", "Sale_Items", "Purchases", "Purchase_Items", "Customers", "Suppliers", "Account_Ledger",
            "Cash_Transactions", "Audit_Log", "Schema_Info", "Backup_History");

    /** What the program's database contains (read only). */
    public record Facts(String schemaVersion, List<String> missingTables, int users, int activeAdmins) {
    }

    private final String databaseName;

    public DatabaseHealthDao(String databaseName) {
        this.databaseName = databaseName;
    }

    /**
     * The database's state as SQL Server reports it ({@code ONLINE}, {@code OFFLINE}, …), from {@code master}; empty
     * when no such database is visible. Also proves the server is reachable and the login accepted.
     *
     * @throws SQLException connection problems (unreachable server, refused login, TLS)
     */
    public Optional<String> databaseState() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection("master");
             PreparedStatement ps = con.prepareStatement("SELECT state_desc FROM sys.databases WHERE name = ?")) {
            ps.setNString(1, databaseName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Schema version, missing critical tables, users and active administrators — on a fresh connection. */
    public Facts facts() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection(databaseName)) {
            String schema = schemaVersion(con);
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
            int users = 0;
            int admins = 0;
            if (!missing.contains("Users") && !missing.contains("Roles")) {
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM dbo.Users), "
                             + "(SELECT COUNT(*) FROM dbo.Users u JOIN dbo.Roles r ON r.role_id = u.role_id "
                             + "WHERE r.role_code = 'ADMIN' AND u.is_active = 1)")) {
                    rs.next();
                    users = rs.getInt(1);
                    admins = rs.getInt(2);
                }
            }
            return new Facts(schema, missing, users, admins);
        }
    }

    /**
     * {@code Schema_Info.schema_version}, or {@code null} when the table does not exist. Two statements: SQL Server
     * compiles a whole statement first, so a query naming a missing table fails even behind a CASE.
     */
    public static String schemaVersion(Connection con) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT OBJECT_ID(N'dbo.Schema_Info', N'U')")) {
            rs.next();
            if (rs.getObject(1) == null) {
                return null;
            }
        }
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT schema_version FROM dbo.Schema_Info WHERE id = 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}

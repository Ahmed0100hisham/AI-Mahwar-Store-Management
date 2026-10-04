package com.almahwar.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates JDBC connections to SQL Server using the settings in {@link AppConfig}.
 * <p>
 * Every call to {@link #getConnection()} returns a new connection; callers
 * (DAO classes) must close it, preferably with try-with-resources:
 * <pre>{@code
 * try (Connection con = DatabaseConnection.getConnection();
 *      PreparedStatement ps = con.prepareStatement(sql)) {
 *     ...
 * }
 * }</pre>
 */
public final class DatabaseConnection {

    private static final Logger LOG = Logger.getLogger(DatabaseConnection.class.getName());

    private static final String URL;
    private static final Properties CONNECTION_PROPS = new Properties();

    static {
        AppConfig cfg = AppConfig.getInstance();

        boolean integrated = cfg.getBoolean("db.integrated-security", false);

        URL = "jdbc:sqlserver://" + cfg.get("db.host", "localhost") + ":" + cfg.getInt("db.port", 1433)
                + ";databaseName=" + cfg.get("db.name", "AlMahwarDB")
                + ";encrypt=" + cfg.getBoolean("db.encrypt", true)
                + ";trustServerCertificate=" + cfg.getBoolean("db.trust-server-certificate", true)
                + ";integratedSecurity=" + integrated
                + ";loginTimeout=" + cfg.getInt("db.login-timeout", 5)
                + ";applicationName=AlMahwarStore";

        if (!integrated) {
            CONNECTION_PROPS.setProperty("user", cfg.get("db.user"));
            CONNECTION_PROPS.setProperty("password", cfg.get("db.password"));
        }
    }

    private DatabaseConnection() {
    }

    /**
     * Opens a new connection to the configured SQL Server database.
     *
     * @throws SQLException if the server is unreachable or the credentials are wrong
     */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, CONNECTION_PROPS);
    }

    /**
     * Checks whether the database is reachable.
     *
     * @return {@code true} if a connection could be opened and validated
     */
    public static boolean testConnection() {
        try (Connection con = getConnection()) {
            return con.isValid(3);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Database connection test failed: " + e.getMessage());
            return false;
        }
    }

    /** JDBC URL without credentials; safe to log or display. */
    public static String getUrl() {
        return URL;
    }
}

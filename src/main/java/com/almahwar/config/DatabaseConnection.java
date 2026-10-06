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
    /** Everything after the database name (without credentials). */
    private static final String URL_OPTIONS;
    private static final String SERVER_URL;
    private static final String DATABASE_NAME;
    private static final Properties CONNECTION_PROPS = new Properties();

    static {
        AppConfig cfg = AppConfig.getInstance();

        boolean integrated = cfg.getBoolean("db.integrated-security", false);

        SERVER_URL = "jdbc:sqlserver://" + cfg.get("db.host", "localhost") + ":" + cfg.getInt("db.port", 1433);
        DATABASE_NAME = cfg.get("db.name", "AlMahwarDB");
        URL_OPTIONS = ";integratedSecurity=" + integrated
                + ";loginTimeout=" + Math.max(1, Math.min(60, cfg.getInt("db.login-timeout", 5)))
                + ";applicationName=AlMahwarStore";
        URL = SERVER_URL + ";databaseName=" + DATABASE_NAME + URL_OPTIONS;

        // TLS: encrypted, and the server certificate validated against the trusted roots of this PC by default.
        // db.trust-server-certificate=true skips that validation (a development server's self-signed certificate).
        boolean encrypt = cfg.getBoolean("db.encrypt", true);
        boolean trustAny = cfg.getBoolean("db.trust-server-certificate", false);
        CONNECTION_PROPS.setProperty("encrypt", String.valueOf(encrypt));
        CONNECTION_PROPS.setProperty("trustServerCertificate", String.valueOf(trustAny));
        String certificateHost = cfg.get("db.host-name-in-certificate");
        if (!certificateHost.isBlank()) {
            CONNECTION_PROPS.setProperty("hostNameInCertificate", certificateHost);
        }
        if (!integrated) {
            CONNECTION_PROPS.setProperty("user", cfg.get("db.user"));
            CONNECTION_PROPS.setProperty("password", cfg.get("db.password"));
        }
        LOG.info("Database: " + getUrl() + " (encrypt=" + encrypt + ", trustServerCertificate=" + trustAny + ")");
        if (!encrypt) {
            LOG.warning("SQL Server connection is NOT encrypted (db.encrypt=false): use only on a trusted network");
        } else if (trustAny) {
            LOG.warning("SQL Server certificate is NOT validated (db.trust-server-certificate=true): development only");
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
     * Opens a connection with the same server and login to another database of the server — {@code master} for
     * administrative statements (backup / restore of the program's database must not run from inside it), or a
     * temporary test database. The name is only accepted as a plain identifier.
     */
    public static Connection getConnection(String databaseName) throws SQLException {
        if (databaseName == null || !databaseName.matches("[A-Za-z0-9_]{1,100}")) {
            throw new SQLException("Invalid database name");
        }
        return DriverManager.getConnection(SERVER_URL + ";databaseName=" + databaseName + URL_OPTIONS,
                CONNECTION_PROPS);
    }

    /** The configured database name ({@code db.name}). */
    public static String databaseName() {
        return DATABASE_NAME;
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

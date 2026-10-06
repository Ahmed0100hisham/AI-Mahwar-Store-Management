package com.almahwar.api.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A throw-away SQL Server database for the integration tests, created from the project's own schema script
 * ({@code database/01_create_database.sql}, schema 1.10.0) under a different name. The real {@code AlMahwarDB} is never
 * opened, changed or restored by any test; only the database named here is created and finally dropped.
 * <p>
 * Connection settings come from the environment only (never from the source): {@code ALMAHWAR_IT_DB_HOST},
 * {@code ALMAHWAR_IT_DB_PORT}, {@code ALMAHWAR_IT_DB_USER}, {@code ALMAHWAR_IT_DB_PASSWORD},
 * {@code ALMAHWAR_IT_DB_TRUST_SERVER_CERTIFICATE} (development server only). The login needs permission to create a
 * database (development server only).
 */
public final class TemporaryDatabase {

    public static final String NAME = "AlMahwarApiIT";

    private static final Pattern GO = Pattern.compile("(?im)^\\s*GO\\s*$");

    private TemporaryDatabase() {
    }

    public static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    public static String host() {
        return env("ALMAHWAR_IT_DB_HOST", "localhost");
    }

    public static String port() {
        return env("ALMAHWAR_IT_DB_PORT", "1433");
    }

    public static String user() {
        return env("ALMAHWAR_IT_DB_USER", "");
    }

    public static String password() {
        return env("ALMAHWAR_IT_DB_PASSWORD", "");
    }

    public static boolean trustServerCertificate() {
        return Boolean.parseBoolean(env("ALMAHWAR_IT_DB_TRUST_SERVER_CERTIFICATE", "false"));
    }

    public static Connection connect(String database) throws SQLException {
        String url = "jdbc:sqlserver://" + host() + ":" + port() + ";databaseName=" + database
                + ";encrypt=true;trustServerCertificate=" + trustServerCertificate() + ";loginTimeout=10";
        Properties p = new Properties();
        p.setProperty("user", user());
        p.setProperty("password", password());
        return DriverManager.getConnection(url, p);
    }

    /** Drops a leftover test database of a previous run, then creates a fresh one from the schema script. */
    public static void create() throws SQLException, IOException {
        if (NAME.equalsIgnoreCase("AlMahwarDB")) {
            throw new IllegalStateException("refusing to touch the real database");
        }
        drop();
        String script = Files.readString(Path.of("..", "database", "01_create_database.sql"), StandardCharsets.UTF_8)
                .replace("AlMahwarDB", NAME);
        try (Connection con = connect("master"); Statement st = con.createStatement()) {
            for (String batch : GO.split(script)) {
                if (!batch.isBlank()) {
                    st.execute(batch);
                }
            }
        }
    }

    public static void drop() throws SQLException {
        try (Connection con = connect("master"); Statement st = con.createStatement()) {
            st.execute("IF DB_ID(N'" + NAME + "') IS NOT NULL BEGIN "
                    + "ALTER DATABASE [" + NAME + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; "
                    + "DROP DATABASE [" + NAME + "]; END");
        }
    }

    public static void exec(String sql, Object... params) throws SQLException {
        try (Connection con = connect(NAME); var ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    public static Object queryOne(String sql, Object... params) throws SQLException {
        try (Connection con = connect(NAME); var ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }
}

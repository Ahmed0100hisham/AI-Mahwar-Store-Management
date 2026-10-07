package com.almahwar.dao;

import com.almahwar.config.DatabaseConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * The process-wide {@link ConnectionProvider} of the business core ({@link BaseDao}, {@link TransactionManager}).
 * <p>
 * The default is exactly the desktop's behaviour since 1.0.0: {@code DatabaseConnection.getConnection()} — same URL,
 * same properties, a new {@code DriverManager} connection per call, no pool. {@code DatabaseConnection} is still
 * initialised lazily, on the first connection, as before.
 */
public final class ConnectionSource {

    /** The desktop's connections, unchanged. */
    public static final ConnectionProvider DESKTOP_DEFAULT = DatabaseConnection::getConnection;

    private static volatile ConnectionProvider provider = DESKTOP_DEFAULT;

    private ConnectionSource() {
    }

    /** A new connection from the installed provider (auto-commit; the caller closes it). */
    public static Connection open() throws SQLException {
        return provider.getConnection();
    }

    /** Installs another provider — once, at startup, before the first database call (not used by the desktop). */
    public static void use(ConnectionProvider connections) {
        provider = Objects.requireNonNull(connections, "connections");
    }

    /** Back to the desktop default. */
    public static void useDefault() {
        provider = DESKTOP_DEFAULT;
    }

    public static ConnectionProvider current() {
        return provider;
    }
}

package com.almahwar.dao;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Where the DAOs and {@link TransactionManager} get their JDBC connections from. Pure JDBC, no framework.
 * <p>
 * The desktop uses the default ({@link ConnectionSource#DESKTOP_DEFAULT}: a new {@code DriverManager} connection per
 * call, configured by {@code config.DatabaseConnection}) and never changes it. Another host of the business core
 * (e.g. a server with a connection pool) installs its own provider once at startup with
 * {@link ConnectionSource#use(ConnectionProvider)}.
 * <p>
 * Contract: every call returns a new, open connection in auto-commit mode, owned by the caller, who closes it.
 */
@FunctionalInterface
public interface ConnectionProvider {

    Connection getConnection() throws SQLException;
}

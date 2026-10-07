package com.almahwar.api.core;

import com.almahwar.dao.ConnectionProvider;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Borrow a fresh pool connection; the core closes it and owns commit/rollback. No Spring transaction binding. */
public final class DataSourceConnectionProvider implements ConnectionProvider {
    private final DataSource dataSource;

    public DataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            // Preserve Phase 1's safe 503 for pool/connection acquisition failures.
            throw new CannotGetJdbcConnectionException("Shared core connection unavailable", e);
        }
    }
}

package com.almahwar.dao;

import com.almahwar.config.DatabaseConnection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs several DAO calls in one database transaction.
 * <pre>{@code
 * TransactionManager.inTransaction(con -> {
 *     int saleId = saleDao.insert(con, sale);
 *     productDao.adjustQuantity(con, productId, qty.negate());
 *     return saleId;
 * });
 * }</pre>
 * Commits if the work completes, rolls back on any exception.
 */
public final class TransactionManager {

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection con) throws SQLException;
    }

    private TransactionManager() {
    }

    public static <T> T inTransaction(TransactionWork<T> work) {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                T result = work.execute(con);
                con.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Transaction failed", e);
        }
    }
}

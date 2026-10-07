package com.almahwar.dao;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC helpers shared by all DAOs. Every statement is a
 * {@link PreparedStatement}; values are never concatenated into SQL.
 * <p>
 * Methods without a {@link Connection} parameter open and close their own
 * connection (auto-commit). Methods that take a {@code Connection} take part
 * in the caller's transaction (see {@link TransactionManager}).
 */
public abstract class BaseDao {

    // ---------- Queries ----------

    protected <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection con = ConnectionSource.open()) {
            return queryList(con, sql, mapper, params);
        } catch (SQLException e) {
            throw new DataAccessException("Query failed: " + sql, e);
        }
    }

    protected <T> List<T> queryList(Connection con, String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Query failed: " + sql, e);
        }
    }

    protected <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = queryList(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    protected <T> Optional<T> queryOne(Connection con, String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = queryList(con, sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Runs a query whose first column is a single number (COUNT, SUM ...). */
    protected long queryLong(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    // ---------- Updates ----------

    /** @return number of affected rows */
    protected int update(String sql, Object... params) {
        try (Connection con = ConnectionSource.open()) {
            return update(con, sql, params);
        } catch (SQLException e) {
            throw new DataAccessException("Update failed: " + sql, e);
        }
    }

    protected int update(Connection con, String sql, Object... params) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Update failed: " + sql, e);
        }
    }

    /** Executes an INSERT and returns the generated IDENTITY value. */
    protected int insert(String sql, Object... params) {
        try (Connection con = ConnectionSource.open()) {
            return insert(con, sql, params);
        } catch (SQLException e) {
            throw new DataAccessException("Insert failed: " + sql, e);
        }
    }

    protected int insert(Connection con, String sql, Object... params) {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DataAccessException("No generated key returned: " + sql);
                }
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Insert failed: " + sql, e);
        }
    }

    /** Fails if an UPDATE/DELETE by id did not touch exactly one row. */
    protected static void requireOneRow(int affected, String entity, Object id) {
        if (affected != 1) {
            throw new DataAccessException(entity + " not found: " + id);
        }
    }

    // ---------- Binding / reading helpers ----------

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value instanceof BigDecimal bd) {
                ps.setBigDecimal(index, bd);
            } else if (value instanceof Boolean b) {
                ps.setBoolean(index, b);
            } else if (value instanceof String s) {
                ps.setNString(index, s);   // NVARCHAR: keeps Arabic text intact
            } else {
                ps.setObject(index, value);   // Integer, Long, LocalDate(Time), null
            }
        }
    }

    /** Escapes LIKE wildcards in user input and wraps it as {@code %text%}. */
    protected static String likeContains(String text) {
        String escaped = text.trim()
                .replace("[", "[[]")
                .replace("%", "[%]")
                .replace("_", "[_]");
        return "%" + escaped + "%";
    }

    protected static Integer getInteger(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }

    protected static LocalDateTime getDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    protected static LocalDate getDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}

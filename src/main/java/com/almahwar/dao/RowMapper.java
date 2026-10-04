package com.almahwar.dao;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the current row of a {@link ResultSet} to an object.
 */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet rs) throws SQLException;
}

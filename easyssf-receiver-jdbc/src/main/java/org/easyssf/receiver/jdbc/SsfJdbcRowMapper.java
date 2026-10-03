package org.easyssf.receiver.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the current row of a result set.
 */
@FunctionalInterface
public interface SsfJdbcRowMapper<T> {

    T map(ResultSet resultSet) throws SQLException;

}

package org.easyssf.receiver.spring.boot.jdbc;

import java.sql.PreparedStatement;
import java.util.List;

import org.easyssf.receiver.jdbc.SsfJdbcDuplicateKeyException;
import org.easyssf.receiver.jdbc.SsfJdbcException;
import org.easyssf.receiver.jdbc.SsfJdbcOperations;
import org.easyssf.receiver.jdbc.SsfJdbcRowMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * {@link SsfJdbcOperations} over a {@link JdbcOperations} (JdbcTemplate), so that the
 * stores take part in the transactions of the application and use Spring's exception
 * translation.
 */
public class JdbcTemplateSsfJdbcOperations implements SsfJdbcOperations {

    private final JdbcOperations jdbc;

    public JdbcTemplateSsfJdbcOperations(JdbcOperations jdbc) {
        Assert.notNull(jdbc, "jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    public int update(String sql, Object... args) {
        try {
            return this.jdbc.update(sql, args);
        }
        catch (DuplicateKeyException ex) {
            throw new SsfJdbcDuplicateKeyException("Duplicate key: " + sql, null, ex);
        }
        catch (DataAccessException ex) {
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), null, ex);
        }
    }

    @Override
    public <T> List<T> query(String sql, SsfJdbcRowMapper<T> mapper, Object... args) {
        try {
            return this.jdbc.query(sql, (row, index) -> mapper.map(row), args);
        }
        catch (DataAccessException ex) {
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), null, ex);
        }
    }

    @Override
    public <T> List<T> query(String sql, int maxRows, SsfJdbcRowMapper<T> mapper, Object... args) {
        try {
            return this.jdbc.query((connection) -> {
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setMaxRows(Math.max(0, maxRows));
                new ArgumentPreparedStatementSetter(args).setValues(statement);
                return statement;
            }, (row, index) -> mapper.map(row));
        }
        catch (DataAccessException ex) {
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), null, ex);
        }
    }

    @Override
    public void execute(String sql) {
        try {
            this.jdbc.execute(sql);
        }
        catch (DataAccessException ex) {
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), null, ex);
        }
    }

    @Override
    public boolean tableExists(String table) {
        try {
            this.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1 = 0", Integer.class);
            return true;
        }
        catch (BadSqlGrammarException ex) {
            return false;
        }
    }

}

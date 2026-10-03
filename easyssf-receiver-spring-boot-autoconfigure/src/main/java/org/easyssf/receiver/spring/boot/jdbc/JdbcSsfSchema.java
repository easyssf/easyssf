package org.easyssf.receiver.spring.boot.jdbc;

import java.util.List;

import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * The tables of the JDBC stores. The same statements, with the default table prefix, are
 * available as {@code schema.sql} next to this class to create the tables with a schema
 * migration tool.
 */
public final class JdbcSsfSchema {

    /**
     * The default prefix of the table names.
     */
    public static final String DEFAULT_TABLE_PREFIX = "EASYSSF_";

    private JdbcSsfSchema() {
    }

    /**
     * The name of the table of the {@link JdbcSsfJtiDedupStore}.
     */
    public static String processedSetTable(String tablePrefix) {
        return validPrefix(tablePrefix) + "PROCESSED_SET";
    }

    /**
     * The name of the table of the {@link JdbcSsfTokenRevocationStore}.
     */
    public static String revocationTable(String tablePrefix) {
        return validPrefix(tablePrefix) + "REVOCATION";
    }

    private static String validPrefix(String tablePrefix) {
        Assert.isTrue(tablePrefix != null && tablePrefix.matches("[A-Za-z0-9_.]*"),
                "The table prefix may only consist of letters, digits, '_' and '.'");
        return tablePrefix;
    }

    /**
     * The statements that create the table of the {@link JdbcSsfJtiDedupStore}.
     */
    public static List<String> createProcessedSetTable(String tablePrefix) {
        String table = processedSetTable(tablePrefix);
        String name = table.replace('.', '_');
        return List.of("""
                CREATE TABLE %s (
                    ISSUER VARCHAR(255) NOT NULL,
                    JTI VARCHAR(255) NOT NULL,
                    PROCESSED_AT BIGINT NOT NULL,
                    CONSTRAINT %s_PK PRIMARY KEY (ISSUER, JTI)
                )""".formatted(table, name), "CREATE INDEX %s_IX1 ON %s (PROCESSED_AT)".formatted(name, table));
    }

    /**
     * The statements that create the table of the {@link JdbcSsfTokenRevocationStore}.
     */
    public static List<String> createRevocationTable(String tablePrefix) {
        String table = revocationTable(tablePrefix);
        String name = table.replace('.', '_');
        return List.of("""
                CREATE TABLE %s (
                    KIND VARCHAR(16) NOT NULL,
                    ID VARCHAR(255) NOT NULL,
                    REVOKED_AT BIGINT NOT NULL,
                    EXPIRES_AT BIGINT NOT NULL,
                    CONSTRAINT %s_PK PRIMARY KEY (KIND, ID)
                )""".formatted(table, name), "CREATE INDEX %s_IX1 ON %s (EXPIRES_AT)".formatted(name, table));
    }

    /**
     * Makes sure a table exists.
     * @param table the name of the table
     * @param createStatements the statements that create it
     * @param create whether to create the table if it does not exist
     * @throws IllegalStateException if the table does not exist and is not to be created
     */
    public static void prepareTable(JdbcOperations jdbc, String table, List<String> createStatements, boolean create) {
        if (exists(jdbc, table)) {
            return;
        }
        if (!create) {
            throw new IllegalStateException("The table " + table + " of the easyssf receiver does not exist. "
                    + "Create it with the statements in classpath:org/easyssf/receiver/spring/boot/jdbc/schema.sql, "
                    + "set easyssf.receiver.jdbc.initialize-schema=always to have it created on startup, "
                    + "or set easyssf.receiver.jdbc.enabled=false to keep the state of the receiver in memory.");
        }
        createStatements.forEach(jdbc::execute);
    }

    private static boolean exists(JdbcOperations jdbc, String table) {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1 = 0", Integer.class);
            return true;
        }
        catch (BadSqlGrammarException ex) {
            return false;
        }
    }

}

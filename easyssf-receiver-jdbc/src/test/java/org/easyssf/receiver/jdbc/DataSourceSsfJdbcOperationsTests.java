package org.easyssf.receiver.jdbc;

import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DataSourceSsfJdbcOperationsTests {

    private final SsfJdbcOperations jdbc = operations();

    @Test
    void reportsDuplicateKeysMissingTablesAndOtherFailures() {
        assertThat(this.jdbc.tableExists("T")).isFalse();
        this.jdbc.execute("CREATE TABLE T (ID VARCHAR(10) PRIMARY KEY, N INT)");
        assertThat(this.jdbc.tableExists("T")).isTrue();
        assertThat(this.jdbc.update("INSERT INTO T (ID, N) VALUES (?, ?)", "a", 1)).isEqualTo(1);
        assertThatExceptionOfType(SsfJdbcDuplicateKeyException.class)
            .isThrownBy(() -> this.jdbc.update("INSERT INTO T (ID, N) VALUES (?, ?)", "a", 2));
        assertThat(this.jdbc.query("SELECT N FROM T WHERE ID = ?", (row) -> row.getInt(1), "a")).containsExactly(1);
        assertThatExceptionOfType(SsfJdbcException.class)
            .isThrownBy(() -> this.jdbc.update("INSERT INTO NOWHERE VALUES (1)"))
            .isNotInstanceOf(SsfJdbcDuplicateKeyException.class);
    }

    private static SsfJdbcOperations operations() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new DataSourceSsfJdbcOperations(dataSource);
    }

}

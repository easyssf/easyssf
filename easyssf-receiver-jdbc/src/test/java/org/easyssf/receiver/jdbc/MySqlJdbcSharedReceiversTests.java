package org.easyssf.receiver.jdbc;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.mysql.MySQLContainer;

import com.mysql.cj.jdbc.MysqlDataSource;

/**
 * Several receivers on one MySQL, in a Testcontainers container shared by the tests of
 * the class. Tagged {@code database}: the normal build excludes it,
 * {@code -Pdatabase-tests} runs it (Docker required).
 */
@Tag("database")
class MySqlJdbcSharedReceiversTests extends AbstractJdbcSharedReceiversTests {

    private static final String IMAGE = "mysql:8.4";

    private static final List<String> TABLES = List.of("EASYSSF_PROCESSED_SET", "EASYSSF_REVOCATION",
            "APP_SSF_PROCESSED_SET", "OTHER_REVOCATION", "EASYSSF_POLL_ACK");

    private static MySQLContainer mysql;

    @BeforeAll
    static void startDatabase() {
        mysql = new MySQLContainer(IMAGE);
        mysql.start();
    }

    @AfterAll
    static void stopDatabase() {
        mysql.stop();
    }

    @Override
    protected SsfJdbcOperations emptyDatabase() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(mysql.getJdbcUrl());
        dataSource.setUser(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        SsfJdbcOperations jdbc = new DataSourceSsfJdbcOperations(dataSource);
        for (String table : TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        return jdbc;
    }

}

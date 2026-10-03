package org.easyssf.receiver.jdbc;

import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;

/**
 * The stores on H2, a new in-memory database for every test.
 */
class JdbcSsfStoresTests extends AbstractJdbcSsfStoresTests {

    @Override
    protected SsfJdbcOperations emptyDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new DataSourceSsfJdbcOperations(dataSource);
    }

}

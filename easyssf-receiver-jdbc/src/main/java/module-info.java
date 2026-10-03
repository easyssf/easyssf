/**
 * The JDBC-backed stores of the receiver, on plain {@code javax.sql.DataSource} or any
 * {@code SsfJdbcOperations}.
 */
module org.easyssf.receiver.jdbc {
    requires transitive org.easyssf.receiver;
    requires transitive java.sql;
    requires org.slf4j;

    exports org.easyssf.receiver.jdbc;
}

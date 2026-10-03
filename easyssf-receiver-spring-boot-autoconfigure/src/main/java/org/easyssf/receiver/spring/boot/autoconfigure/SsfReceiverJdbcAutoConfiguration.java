package org.easyssf.receiver.spring.boot.autoconfigure;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfExpiringStore;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfJtiDedupStore;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfSchema;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfTokenRevocationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * {@link AutoConfiguration Auto-configuration} that keeps the state of the receiver in
 * the database of the application, if it has a single {@link JdbcOperations}
 * (JdbcTemplate): which SETs were processed and which sessions and subjects were revoked.
 * All instances of the application then share that state.
 *
 * <p>
 * It is processed before the auto-configurations that fall back to the stores that keep
 * the state in memory.
 */
@AutoConfiguration(before = { SsfReceiverAutoConfiguration.class, SsfReceiverResourceServerAutoConfiguration.class },
        afterName = { "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration" })
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass({ JdbcOperations.class, EmbeddedDatabaseConnection.class })
@ConditionalOnSingleCandidate(JdbcOperations.class)
@ConditionalOnBooleanProperty(name = { "easyssf.receiver.enabled", "easyssf.receiver.jdbc.enabled" },
        matchIfMissing = true)
@EnableConfigurationProperties(SsfReceiverProperties.class)
public final class SsfReceiverJdbcAutoConfiguration {

    private static final Log logger = LogFactory.getLog(SsfReceiverJdbcAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SsfJtiDedupStore.class)
    @ConditionalOnBooleanProperty(name = "easyssf.receiver.dedup.enabled", matchIfMissing = true)
    JdbcSsfJtiDedupStore jdbcSsfJtiDedupStore(JdbcOperations jdbc, ObjectProvider<DataSource> dataSource,
            SsfReceiverProperties properties) {
        String tablePrefix = properties.getJdbc().getTablePrefix();
        String table = JdbcSsfSchema.processedSetTable(tablePrefix);
        JdbcSsfSchema.prepareTable(jdbc, table, JdbcSsfSchema.createProcessedSetTable(tablePrefix),
                createTables(properties, dataSource));
        JdbcSsfJtiDedupStore store = new JdbcSsfJtiDedupStore(jdbc, tablePrefix);
        store.setRetention(properties.getDedup().getRetention());
        logger.info("Processed SETs are remembered in the table " + table);
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(SsfTokenRevocationStore.class)
    @ConditionalOnClass(name = {
            "org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken",
            "org.springframework.security.oauth2.jwt.Jwt" })
    @ConditionalOnBooleanProperty(name = "easyssf.receiver.resource-server.enabled", matchIfMissing = true)
    JdbcSsfTokenRevocationStore jdbcSsfTokenRevocationStore(JdbcOperations jdbc, ObjectProvider<DataSource> dataSource,
            SsfReceiverProperties properties) {
        String tablePrefix = properties.getJdbc().getTablePrefix();
        String table = JdbcSsfSchema.revocationTable(tablePrefix);
        JdbcSsfSchema.prepareTable(jdbc, table, JdbcSsfSchema.createRevocationTable(tablePrefix),
                createTables(properties, dataSource));
        logger.info("Revoked sessions and subjects are kept in the table " + table);
        return new JdbcSsfTokenRevocationStore(jdbc, tablePrefix, properties.getResourceServer().getRevocationTtl());
    }

    /**
     * Purges the expired rows of the stores every
     * {@code easyssf.receiver.jdbc.cleanup-interval}.
     */
    @Bean
    @ConditionalOnMissingBean
    JdbcSsfStoreCleanup jdbcSsfStoreCleanup(ObjectProvider<JdbcSsfExpiringStore> stores,
            SsfReceiverProperties properties) {
        JdbcSsfStoreCleanup cleanup = new JdbcSsfStoreCleanup(stores.orderedStream().toList(),
                properties.getJdbc().getCleanupInterval());
        if (cleanup.isEnabled()) {
            logger.info(
                    "Expired rows of the easyssf tables are purged every " + properties.getJdbc().getCleanupInterval());
        }
        return cleanup;
    }

    private static boolean createTables(SsfReceiverProperties properties, ObjectProvider<DataSource> dataSource) {
        return switch (properties.getJdbc().getInitializeSchema()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case EMBEDDED -> {
                DataSource unique = dataSource.getIfUnique();
                yield unique != null && EmbeddedDatabaseConnection.isEmbedded(unique);
            }
        };
    }

}

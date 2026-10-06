package org.easyssf.receiver.spring.boot.jdbc;

import java.time.Duration;
import java.util.UUID;

import org.easyssf.receiver.jdbc.JdbcSsfJtiDedupStore;
import org.easyssf.receiver.jdbc.JdbcSsfSchema;
import org.easyssf.receiver.jdbc.SsfJdbcDuplicateKeyException;
import org.easyssf.receiver.jdbc.SsfJdbcOperations;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The stores on a JdbcTemplate: Spring's exceptions are mapped to the ones the stores
 * expect.
 */
class JdbcTemplateSsfJdbcOperationsTests {

    private final SsfJdbcOperations jdbc = new JdbcTemplateSsfJdbcOperations(
            new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")));

    @Test
    void mapsDuplicateKeysAndMissingTables() {
        String prefix = JdbcSsfSchema.DEFAULT_TABLE_PREFIX;
        assertThat(this.jdbc.tableExists(JdbcSsfSchema.processedSetTable(prefix))).isFalse();
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.processedSetTable(prefix),
                JdbcSsfSchema.createProcessedSetTable(prefix), true, null);
        assertThat(this.jdbc.tableExists(JdbcSsfSchema.processedSetTable(prefix))).isTrue();
        this.jdbc.update(
                "INSERT INTO EASYSSF_PROCESSED_SET (ISSUER, JTI, STATE, STATE_CHANGED_AT) VALUES (?, ?, 'PROCESSED', ?)",
                "i", "j", System.currentTimeMillis());
        assertThatExceptionOfType(SsfJdbcDuplicateKeyException.class).isThrownBy(() -> this.jdbc.update(
                "INSERT INTO EASYSSF_PROCESSED_SET (ISSUER, JTI, STATE, STATE_CHANGED_AT) VALUES (?, ?, 'PROCESSED', ?)",
                "i", "j", System.currentTimeMillis()));
        JdbcSsfJtiDedupStore store = new JdbcSsfJtiDedupStore(this.jdbc, prefix);
        store.setRetention(Duration.ofDays(1));
        assertThat(this.jdbc.query("SELECT COUNT(*) FROM EASYSSF_PROCESSED_SET", (row) -> row.getInt(1)))
            .containsExactly(1);
        assertThat(store.purgeExpired()).isZero();
    }

    @Test
    void limitsAQueryThroughTheDriver() {
        String prefix = JdbcSsfSchema.DEFAULT_TABLE_PREFIX;
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.pollAckTable(prefix),
                JdbcSsfSchema.createPollAckTable(prefix), true, null);
        for (int i = 0; i < 3; i++) {
            this.jdbc.update("INSERT INTO EASYSSF_POLL_ACK (ISSUER, JTI, RECORDED_AT) VALUES (?, ?, ?)", "i",
                    "jti-" + i, (long) i);
        }
        assertThat(this.jdbc.query("SELECT JTI FROM EASYSSF_POLL_ACK WHERE ISSUER = ? ORDER BY RECORDED_AT", 2,
                (row) -> row.getString(1), "i"))
            .containsExactly("jti-0", "jti-1");
        assertThat(this.jdbc.query("SELECT JTI FROM EASYSSF_POLL_ACK WHERE ISSUER = ? ORDER BY RECORDED_AT",
                (row) -> row.getString(1), "i"))
            .hasSize(3);
    }

}

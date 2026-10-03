package org.easyssf.receiver.jdbc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.easyssf.core.event.SsfEventToken;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JdbcSsfStoresTests {

    private static final String ISSUER = "https://idp.example";

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private static final String PREFIX = JdbcSsfSchema.DEFAULT_TABLE_PREFIX;

    private final MutableClock clock = new MutableClock(NOW);

    private SsfJdbcOperations jdbc;

    @BeforeEach
    void createDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        this.jdbc = new DataSourceSsfJdbcOperations(dataSource);
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.processedSetTable(PREFIX),
                JdbcSsfSchema.createProcessedSetTable(PREFIX), true, null);
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.revocationTable(PREFIX),
                JdbcSsfSchema.createRevocationTable(PREFIX), true, null);
    }

    private JdbcSsfJtiDedupStore dedupStore() {
        JdbcSsfJtiDedupStore store = new JdbcSsfJtiDedupStore(this.jdbc, PREFIX);
        store.setRetention(Duration.ofDays(1));
        store.setClock(this.clock);
        return store;
    }

    private JdbcSsfTokenRevocationStore revocationStore() {
        JdbcSsfTokenRevocationStore store = new JdbcSsfTokenRevocationStore(this.jdbc, PREFIX, Duration.ofMinutes(10));
        store.setClock(this.clock);
        return store;
    }

    @Test
    void schemaFileMatchesTheStatementsThatCreateTheTables() throws Exception {
        String schema = new String(getClass().getResourceAsStream("schema.sql").readAllBytes(), StandardCharsets.UTF_8);
        List<String> statements = Arrays.stream(schema.replaceAll("(?m)^--.*$", "").split(";"))
            .map(JdbcSsfStoresTests::normalize)
            .filter((statement) -> !statement.isEmpty())
            .toList();
        List<String> expected = new ArrayList<>(JdbcSsfSchema.createProcessedSetTable(PREFIX));
        expected.addAll(JdbcSsfSchema.createRevocationTable(PREFIX));
        assertThat(statements).isEqualTo(expected.stream().map(JdbcSsfStoresTests::normalize).toList());
    }

    private static String normalize(String statement) {
        return statement.replaceAll("\\s+", " ").trim();
    }

    @Test
    void preparingTablesIsRepeatableAndReportsMissingTables() {
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.revocationTable(PREFIX),
                JdbcSsfSchema.createRevocationTable(PREFIX), true, null);
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.revocationTable(PREFIX),
                JdbcSsfSchema.createRevocationTable(PREFIX), false, null);
        assertThatIllegalStateException()
            .isThrownBy(() -> JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.revocationTable("OTHER_"),
                    JdbcSsfSchema.createRevocationTable("OTHER_"), false, null))
            .withMessageContaining("OTHER_REVOCATION")
            .withMessageContaining("schema.sql");
    }

    @Test
    void tablePrefixIsConfigurableButRestricted() {
        JdbcSsfSchema.prepareTable(this.jdbc, JdbcSsfSchema.processedSetTable("APP_SSF_"),
                JdbcSsfSchema.createProcessedSetTable("APP_SSF_"), true, null);
        JdbcSsfJtiDedupStore store = new JdbcSsfJtiDedupStore(this.jdbc, "APP_SSF_");
        assertThat(store.seenBefore(set("jti-1"))).isFalse();
        assertThat(count("APP_SSF_PROCESSED_SET")).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new JdbcSsfJtiDedupStore(this.jdbc, "X; DROP TABLE Y;"));
    }

    @Test
    void setIsSeenBeforeOnceItWasRecorded() {
        JdbcSsfJtiDedupStore store = dedupStore();
        assertThat(store.seenBefore(set("jti-1"))).isFalse();
        assertThat(store.seenBefore(set("jti-1"))).isTrue();
        assertThat(store.seenBefore(set("jti-2"))).isFalse();
        // a jti is only unique per issuer
        assertThat(store.seenBefore(set("https://other.example", "jti-1"))).isFalse();
    }

    @Test
    void processedSetsAreSharedBetweenInstances() {
        assertThat(dedupStore().seenBefore(set("jti-1"))).isFalse();
        assertThat(dedupStore().seenBefore(set("jti-1"))).isTrue();
    }

    @Test
    void forgottenSetIsNotSeenBefore() {
        JdbcSsfJtiDedupStore store = dedupStore();
        store.seenBefore(set("jti-1"));
        store.forget(set("jti-1"));
        assertThat(store.seenBefore(set("jti-1"))).isFalse();
    }

    @Test
    void processedSetsAreForgottenAfterTheRetentionTime() {
        JdbcSsfJtiDedupStore store = dedupStore();
        store.seenBefore(set("jti-1"));
        this.clock.advance(Duration.ofHours(23));
        assertThat(store.seenBefore(set("jti-1"))).isTrue();
        this.clock.advance(Duration.ofHours(2));
        assertThat(store.seenBefore(set("jti-1"))).isFalse();
        assertThat(count("EASYSSF_PROCESSED_SET")).isEqualTo(1);
    }

    @Test
    void revokedSessionRevokesItsTokensWheneverTheyWereIssued() {
        JdbcSsfTokenRevocationStore store = revocationStore();
        store.revokeSession(ISSUER, "session-1");
        assertThat(store.isSessionRevoked(ISSUER, "session-1")).isTrue();
        assertThat(store.isRevoked(ISSUER, "session-1", "alice", NOW.plusSeconds(60))).isTrue();
        assertThat(store.isRevoked(ISSUER, "session-2", "alice", NOW.minusSeconds(60))).isFalse();
        assertThat(store.isRevoked(ISSUER, null, null, NOW)).isFalse();
    }

    @Test
    void revokedSubjectRevokesTokensIssuedUpToTheRevocation() {
        JdbcSsfTokenRevocationStore store = revocationStore();
        store.revokeSubject(ISSUER, "alice", NOW);
        assertThat(store.getSubjectRevokedAt(ISSUER, "alice")).isEqualTo(NOW);
        assertThat(store.isRevoked(ISSUER, "session-1", "alice", NOW.minusSeconds(60))).isTrue();
        assertThat(store.isRevoked(ISSUER, "session-1", "alice", NOW)).isTrue();
        assertThat(store.isRevoked(ISSUER, "session-1", "alice", null)).isTrue();
        assertThat(store.isRevoked(ISSUER, "session-2", "alice", NOW.plusSeconds(1))).isFalse();
        assertThat(store.isRevoked(ISSUER, "session-3", "bob", NOW.minusSeconds(60))).isFalse();
        // a session and a subject may have the same identifier
        assertThat(store.isSessionRevoked(ISSUER, "alice")).isFalse();
        // revocations are scoped to the issuer
        assertThat(store.getSubjectRevokedAt("https://other.example", "alice")).isNull();
        assertThat(store.isRevoked("https://other.example", "session-1", "alice", NOW.minusSeconds(60))).isFalse();
        assertThat(store.isRevoked(null, "session-1", "alice", NOW.minusSeconds(60))).isFalse();
    }

    @Test
    void revocationsAreSharedBetweenInstances() {
        revocationStore().revokeSession(ISSUER, "session-1");
        revocationStore().revokeSubject(ISSUER, "alice", NOW);
        JdbcSsfTokenRevocationStore other = revocationStore();
        assertThat(other.isRevoked(ISSUER, "session-1", "bob", NOW)).isTrue();
        assertThat(other.isRevoked(ISSUER, "session-2", "alice", NOW.minusSeconds(1))).isTrue();
    }

    @Test
    void eventDeliveredLateDoesNotShortenMoreRecentRevocation() {
        JdbcSsfTokenRevocationStore store = revocationStore();
        store.revokeSubject(ISSUER, "alice", NOW);
        store.revokeSubject(ISSUER, "alice", NOW.minusSeconds(120));
        assertThat(store.getSubjectRevokedAt(ISSUER, "alice")).isEqualTo(NOW);
        store.revokeSubject(ISSUER, "alice", NOW.plusSeconds(30));
        assertThat(store.getSubjectRevokedAt(ISSUER, "alice")).isEqualTo(NOW.plusSeconds(30));
        assertThat(count("EASYSSF_REVOCATION")).isEqualTo(1);
    }

    @Test
    void revocationsExpireAndAreRemoved() {
        JdbcSsfTokenRevocationStore store = revocationStore();
        store.revokeSession(ISSUER, "session-1");
        store.revokeSubject(ISSUER, "alice", NOW);
        this.clock.advance(Duration.ofMinutes(9));
        assertThat(store.isSessionRevoked(ISSUER, "session-1")).isTrue();
        assertThat(store.getSubjectRevokedAt(ISSUER, "alice")).isEqualTo(NOW);
        this.clock.advance(Duration.ofMinutes(2));
        assertThat(store.isSessionRevoked(ISSUER, "session-1")).isFalse();
        assertThat(store.getSubjectRevokedAt(ISSUER, "alice")).isNull();
        assertThat(store.isRevoked(ISSUER, "session-1", "alice", NOW.minusSeconds(60))).isFalse();
        store.revokeSession(ISSUER, "session-2");
        assertThat(this.jdbc.query("SELECT ID FROM EASYSSF_REVOCATION", (row) -> row.getString(1)))
            .containsExactly("session-2");
    }

    @Test
    void purgeExpiredDeletesOnlyTheExpiredRows() {
        // processed SETs are retained for a day in this test
        JdbcSsfJtiDedupStore dedupStore = dedupStore();
        dedupStore.seenBefore(set("old"));
        this.clock.advance(Duration.ofHours(12));
        dedupStore.seenBefore(set("recent"));
        this.clock.advance(Duration.ofHours(12).plusSeconds(1));
        assertThat(dedupStore.purgeExpired()).isEqualTo(1);
        assertThat(this.jdbc.query("SELECT JTI FROM EASYSSF_PROCESSED_SET", (row) -> row.getString(1)))
            .containsExactly("recent");
        assertThat(dedupStore.purgeExpired()).isZero();

        // revocations for ten minutes
        JdbcSsfTokenRevocationStore revocationStore = revocationStore();
        revocationStore.revokeSession(ISSUER, "old-session");
        this.clock.advance(Duration.ofMinutes(5));
        revocationStore.revokeSession(ISSUER, "recent-session");
        this.clock.advance(Duration.ofMinutes(5));
        assertThat(revocationStore.purgeExpired()).isEqualTo(1);
        assertThat(this.jdbc.query("SELECT ID FROM EASYSSF_REVOCATION", (row) -> row.getString(1)))
            .containsExactly("recent-session");
        assertThat(revocationStore.purgeExpired()).isZero();
    }

    @Test
    void cleanupPurgesAllStoresAndRunsOnlyWhenEnabled() {
        JdbcSsfJtiDedupStore dedupStore = dedupStore();
        JdbcSsfTokenRevocationStore revocationStore = revocationStore();
        dedupStore.seenBefore(set("old"));
        revocationStore.revokeSession(ISSUER, "old-session");
        this.clock.advance(Duration.ofDays(2));

        JdbcSsfStoreCleanup off = new JdbcSsfStoreCleanup(List.of(dedupStore, revocationStore), Duration.ZERO);
        assertThat(off.isEnabled()).isFalse();
        off.start();
        assertThat(off.isRunning()).isFalse();
        assertThat(off.purgeExpired()).isEqualTo(2);

        JdbcSsfStoreCleanup cleanup = new JdbcSsfStoreCleanup(List.of(dedupStore, revocationStore),
                Duration.ofMinutes(15));
        assertThat(cleanup.isEnabled()).isTrue();
        cleanup.start();
        assertThat(cleanup.isRunning()).isTrue();
        cleanup.stop();
        assertThat(cleanup.isRunning()).isFalse();
    }

    private int count(String table) {
        return this.jdbc.query("SELECT COUNT(*) FROM " + table, (row) -> row.getInt(1)).get(0);
    }

    private static SsfEventToken set(String jti) {
        return set("https://idp.example", jti);
    }

    private static SsfEventToken set(String issuer, String jti) {
        return new SsfEventToken(jti, issuer, NOW, List.of(), Map.of(), null, null, Map.of());
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

    }

}

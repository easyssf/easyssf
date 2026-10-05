package org.easyssf.receiver.jdbc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Several receivers (instances of one application) polling the same stream and sharing
 * the JDBC stores: a SET is handled once, and an acknowledgement one instance could not
 * deliver leaves with the next poll of another.
 */
abstract class AbstractJdbcSharedReceiversTests {

    /**
     * One transmitter per test: a static one would be shared by the subclasses for the
     * different databases and closed by the first of them to finish.
     */
    private TestTransmitter transmitter;

    private static final String PREFIX = JdbcSsfSchema.DEFAULT_TABLE_PREFIX;

    private JdbcSsfJtiDedupStore dedupStore;

    private JdbcSsfPollAckStore ackStore;

    /**
     * Returns operations on an empty database: none of the tables of the tests exist.
     */
    protected abstract SsfJdbcOperations emptyDatabase();

    @BeforeEach
    void setUp() {
        this.transmitter = new TestTransmitter();
        SsfJdbcOperations jdbc = emptyDatabase();
        JdbcSsfSchema.prepareTable(jdbc, JdbcSsfSchema.processedSetTable(PREFIX),
                JdbcSsfSchema.createProcessedSetTable(PREFIX), true, null);
        JdbcSsfSchema.prepareTable(jdbc, JdbcSsfSchema.pollAckTable(PREFIX), JdbcSsfSchema.createPollAckTable(PREFIX),
                true, null);
        this.dedupStore = new JdbcSsfJtiDedupStore(jdbc, PREFIX);
        this.ackStore = new JdbcSsfPollAckStore(jdbc, PREFIX);
    }

    @AfterEach
    void stopTransmitter() {
        this.transmitter.close();
    }

    @Test
    void setIsHandledOnceAndAcknowledgedByWhicheverInstancePollsNext() {
        Receiver a = receiver();
        Receiver b = receiver();
        String jti = queue("session-1");
        // a handles the SET, then loses the transmitter before the acknowledgement leaves
        a.afterHandling = () -> this.transmitter.setAvailable(false);

        assertThatIllegalStateException().isThrownBy(a.poller::pollNow);

        assertThat(a.handled).containsExactly(jti);
        assertThat(this.transmitter.acknowledgedSets()).isEmpty();
        // the acknowledgement waits in the shared store, visible to both instances
        assertThat(a.poller.getPendingAckCount()).isEqualTo(1);
        assertThat(b.poller.getPendingAckCount()).isEqualTo(1);

        // b polls: its request carries a's acknowledgement from the shared store, so the
        // transmitter
        // releases the SET before answering and b does not even see it
        this.transmitter.setAvailable(true);
        assertThat(b.poller.pollNow()).isZero();

        assertThat(b.handled).isEmpty();
        assertThat(this.transmitter.acknowledgedSets()).containsExactly(jti);
        assertThat(a.poller.getPendingAckCount()).isZero();
        assertThat(b.poller.getPendingAckCount()).isZero();
        assertThat(this.transmitter.queuedSetCount()).isZero();
    }

    @Test
    void errorReportOfOneInstanceLeavesWithTheNextPollOfAnother() {
        Receiver a = receiver();
        Receiver b = receiver();
        String jti = queue("session-1");
        this.transmitter.queueSet("broken", "not-a-set");
        a.afterHandling = () -> this.transmitter.setAvailable(false);

        assertThatIllegalStateException().isThrownBy(a.poller::pollNow);
        assertThat(this.transmitter.reportedErrors()).isEmpty();

        this.transmitter.setAvailable(true);
        b.poller.pollNow();

        assertThat(this.transmitter.acknowledgedSets()).containsExactly(jti);
        assertThat(this.transmitter.reportedErrors()).containsOnlyKeys("broken");
        assertThat(b.handled).isEmpty();
    }

    @Test
    void setDeliveredAgainLaterIsNotHandledTwiceByAnyInstance() {
        Receiver a = receiver();
        Receiver b = receiver();
        String set = this.transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque("session-1"));
        String jti = jti(set);
        this.transmitter.queueSet(set);
        assertThat(a.poller.pollNow()).isEqualTo(1);
        assertThat(this.transmitter.acknowledgedSets()).containsExactly(jti);

        // the transmitter delivers the same SET once more, to the other instance
        this.transmitter.queueSet(jti, set);
        assertThat(b.poller.pollNow()).isEqualTo(1);

        assertThat(a.handled).containsExactly(jti);
        assertThat(b.handled).isEmpty();
        assertThat(this.transmitter.acknowledgedSets()).containsExactly(jti, jti);
    }

    @Test
    void setBeingHandledByOneInstanceIsNeitherHandledNorAcknowledgedByAnother() throws Exception {
        Receiver a = receiver();
        Receiver b = receiver();
        String jti = queue("session-1");
        CountDownLatch aIsHandling = new CountDownLatch(1);
        CountDownLatch bHasPolled = new CountDownLatch(1);
        // a's handler blocks until b has polled, then fails
        a.afterHandling = () -> {
            aIsHandling.countDown();
            try {
                assertThat(bHasPolled.await(10, TimeUnit.SECONDS)).isTrue();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("store is down");
        };
        Thread aPolls = Thread.ofVirtual().start(() -> {
            try {
                a.poller.pollNow();
            }
            catch (RuntimeException ex) {
                // the failed handler leaves the SET unacknowledged
            }
        });
        assertThat(aIsHandling.await(10, TimeUnit.SECONDS)).isTrue();

        // b receives the SET while a is still on it: in progress, neither handled nor
        // acknowledged
        assertThat(b.poller.pollNow()).isEqualTo(1);
        assertThat(b.handled).isEmpty();
        assertThat(transmitter.acknowledgedSets()).isEmpty();
        bHasPolled.countDown();
        aPolls.join(Duration.ofSeconds(10));
        assertThat(transmitter.acknowledgedSets()).isEmpty();

        // the redelivery is handled, by whichever instance polls
        assertThat(b.poller.pollNow()).isEqualTo(1);
        assertThat(b.handled).containsExactly(jti);
        assertThat(transmitter.acknowledgedSets()).containsExactly(jti);
    }

    private Receiver receiver() {
        Receiver receiver = new Receiver();
        SsfHttpClient http = new JdkSsfHttpClient();
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(this.transmitter.issuer(), transmitter::jwksUri, http);
        SsfEventHandler handler = (eventContext) -> {
            receiver.handled.add(eventContext.eventToken().jti());
            receiver.afterHandling.run();
        };
        SsfSetProcessor processor = new SsfSetProcessor(verifier, this.dedupStore, List.of(handler));
        receiver.poller = new SsfPoller(http, () -> TestTransmitter.ACCESS_TOKEN,
                () -> URI.create(this.transmitter.pollUri()), processor);
        receiver.poller.setAckStore(this.ackStore);
        receiver.poller.setTransmitter(this.transmitter.issuer());
        receiver.poller.setFlushOnStop(false);
        return receiver;
    }

    private String queue(String sessionId) {
        String set = this.transmitter.set("CaepSessionRevoked", SsfSubjectIdentifiers.opaque(sessionId));
        this.transmitter.queueSet(set);
        return jti(set);
    }

    /**
     * The {@code jti} of a SET, read from its payload without a JOSE library: this module
     * does not read Nimbus on the module path.
     */
    private static String jti(String encodedSet) {
        String payload = new String(Base64.getUrlDecoder().decode(encodedSet.split("\\.")[1]), StandardCharsets.UTF_8);
        Matcher jti = Pattern.compile("\"jti\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        assertThat(jti.find()).isTrue();
        return jti.group(1);
    }

    /**
     * One instance of the application: its poller and what its handler saw.
     */
    private static final class Receiver {

        private SsfPoller poller;

        private final List<String> handled = new ArrayList<>();

        /** runs after a SET was handled, before it is acknowledged */
        private Runnable afterHandling = () -> {
        };

    }

}

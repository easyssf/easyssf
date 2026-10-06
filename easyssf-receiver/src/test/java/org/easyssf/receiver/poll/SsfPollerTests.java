package org.easyssf.receiver.poll;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.MicrometerSsfReceiverMetrics;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfPollerTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final List<String> handled = new ArrayList<>();

    private final List<RuntimeException> failures = new ArrayList<>();

    /** runs after a SET was handled, before it is acknowledged */
    private Runnable afterHandling = () -> {
    };

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SsfPoller poller;

    @BeforeEach
    void setUp() {
        transmitter.reset();
        transmitter.setAvailable(true);
        this.poller = poller(() -> URI.create(transmitter.pollUri()), TestTransmitter.ACCESS_TOKEN);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    private SsfPoller poller(java.util.function.Supplier<URI> endpoint, String accessToken) {
        return poller(endpoint, () -> accessToken);
    }

    private SsfPoller poller(java.util.function.Supplier<URI> endpoint, SsfTransmitterTokenProvider tokenProvider) {
        SsfEventHandler handler = (eventContext) -> {
            if (!this.failures.isEmpty()) {
                throw this.failures.remove(0);
            }
            this.handled.add(eventContext.eventToken().jti());
            this.afterHandling.run();
        };
        SsfHttpClient http = new JdkSsfHttpClient();
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(), transmitter::jwksUri, http);
        MicrometerSsfReceiverMetrics metrics = new MicrometerSsfReceiverMetrics(this.meterRegistry);
        SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(100), List.of(handler));
        processor.setMetrics(metrics);
        SsfPoller poller = new SsfPoller(http, tokenProvider, endpoint, processor);
        poller.setMetrics(metrics);
        return poller;
    }

    @Test
    void startIsIdempotentAndStopEndsThePollingThread() {
        this.poller.setInitialDelay(Duration.ofHours(1));
        this.poller.start();
        this.poller.start();
        assertThat(this.poller.isRunning()).isTrue();
        assertThat(pollerThreads()).hasSize(1);
        this.poller.stop();
        this.poller.stop();
        assertThat(this.poller.isRunning()).isFalse();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(pollerThreads()).isEmpty());
    }

    private static List<Thread> pollerThreads() {
        return pollerThreads("ssf-poller");
    }

    private static List<Thread> pollerThreads(String name) {
        return Thread.getAllStackTraces()
            .keySet()
            .stream()
            .filter((thread) -> thread.getName().equals(name))
            .filter(Thread::isAlive)
            .toList();
    }

    @Test
    void pollsOnTheThreadOfTheGivenFactory() {
        this.poller.setThreadFactory(Thread.ofPlatform().name("ssf-poller-idp").daemon().factory());
        this.poller.setInitialDelay(Duration.ofHours(1));
        this.poller.start();
        assertThat(pollerThreads("ssf-poller-idp")).hasSize(1);
        assertThat(pollerThreads()).isEmpty();
        this.poller.stop();
        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(pollerThreads("ssf-poller-idp")).isEmpty());
    }

    @Test
    void pollsOnAVirtualThreadIfTheFactoryMakesOne() {
        transmitter.setLongPollHold(Duration.ofSeconds(5));
        this.poller.setLongPolling(Duration.ofSeconds(5));
        this.poller.setThreadFactory(Thread.ofVirtual().name("ssf-poller-virtual").factory());
        this.poller.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() >= 1);
        String jti = queue("session-1");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(this.handled).containsExactly(jti));
        this.poller.stop();
        assertThat(this.poller.isRunning()).isFalse();
    }

    @Test
    void handlesAndAcknowledgesAvailableSets() {
        String first = queue("session-1");
        String second = queue("session-2");

        assertThat(this.poller.pollNow()).isEqualTo(2);

        assertThat(this.handled).containsExactly(first, second);
        assertThat(transmitter.acknowledgedSets()).containsExactly(first, second);
        assertThat(this.poller.pollNow()).isZero();
        assertThat(counter("easyssf.receiver.sets", "delivery", "poll", "outcome", "handled")).isEqualTo(2);
        assertThat(counter("easyssf.receiver.events", "delivery", "poll", "event", "CaepSessionRevoked")).isEqualTo(2);
        assertThat(this.meterRegistry.get("easyssf.receiver.poll").tag("outcome", "success").timer().count())
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    void fetchesAllSetsWhenMoreAreAvailableThanFitInOneRequest() {
        List<String> queued = List.of(queue("session-1"), queue("session-2"), queue("session-3"));
        this.poller.setMaxEvents(1);

        assertThat(this.poller.pollNow()).isEqualTo(3);

        assertThat(this.handled).containsExactlyElementsOf(queued);
        assertThat(transmitter.acknowledgedSets()).containsExactlyElementsOf(queued);
    }

    @Test
    void reportsInvalidSetToTheTransmitter() {
        String valid = queue("session-1");
        transmitter.queueSet("broken", "not-a-set");

        assertThat(this.poller.pollNow()).isEqualTo(2);

        assertThat(this.handled).containsExactly(valid);
        assertThat(transmitter.acknowledgedSets()).containsExactly(valid);
        assertThat(transmitter.reportedErrors()).containsOnlyKeys("broken");
        assertThat(transmitter.reportedErrors().get("broken"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .containsEntry("err", "invalid_request");
        assertThat(counter("easyssf.receiver.sets", "delivery", "poll", "outcome", "invalid")).isEqualTo(1);
    }

    @Test
    void doesNotAcknowledgeSetThatCouldNotBeHandledSoThatItIsDeliveredAgain() {
        String jti = queue("session-1");
        this.failures.add(new IllegalStateException("store is down"));

        assertThat(this.poller.pollNow()).isEqualTo(1);
        assertThat(this.handled).isEmpty();
        assertThat(transmitter.acknowledgedSets()).isEmpty();

        assertThat(this.poller.pollNow()).isEqualTo(1);
        assertThat(this.handled).containsExactly(jti);
        assertThat(transmitter.acknowledgedSets()).containsExactly(jti);
    }

    @Test
    void obtainsNewAccessTokenWhenTheTransmitterRejectsTheCurrentOne() {
        ClientCredentialsSsfTransmitterTokenProvider tokenProvider = new ClientCredentialsSsfTransmitterTokenProvider(
                new JdkSsfHttpClient(), URI.create(transmitter.tokenUri()), TestTransmitter.CLIENT_ID,
                TestTransmitter.CLIENT_SECRET);
        SsfPoller poller = poller(() -> URI.create(transmitter.pollUri()), tokenProvider);
        assertThat(poller.pollNow()).isZero();
        String jti = queue("session-1");

        transmitter.expireAccessTokens();

        assertThat(poller.pollNow()).isEqualTo(1);
        assertThat(transmitter.acknowledgedSets()).containsExactly(jti);
    }

    @Test
    void doesNotPollWhileThePollEndpointIsUnknown() {
        queue("session-1");
        assertThat(poller(() -> null, TestTransmitter.ACCESS_TOKEN).pollNow()).isZero();
        assertThat(this.handled).isEmpty();
    }

    @Test
    void pollsSoonAfterTheEndpointBecomesKnownInsteadOfWaitingTheInterval() {
        AtomicReference<URI> endpoint = new AtomicReference<>();
        SsfPoller waiting = poller(endpoint::get, TestTransmitter.ACCESS_TOKEN);
        waiting.setInterval(Duration.ofHours(1));
        waiting.setEndpointRetry(Duration.ofMillis(100));
        String jti = queue("session-1");
        waiting.start();
        try {
            // the stream is still being registered: nothing is polled
            await().during(Duration.ofMillis(300)).untilAsserted(() -> assertThat(this.handled).isEmpty());
            // registered: the poller notices within the retry, not after the interval
            endpoint.set(URI.create(transmitter.pollUri()));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(this.handled).containsExactly(jti));
        }
        finally {
            waiting.stop();
        }
    }

    @Test
    void reportsFailedPollRequest() {
        SsfPoller unauthenticated = poller(() -> URI.create(transmitter.pollUri()), "wrong-token");
        assertThatIllegalStateException().isThrownBy(unauthenticated::pollNow).withMessageContaining("401");
        assertThat(this.meterRegistry.get("easyssf.receiver.poll").tag("outcome", "failure").timer().count())
            .isEqualTo(1);
    }

    @Test
    void acknowledgementsWaitInTheStoreUntilARequestCarriedThem() {
        InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();
        this.poller.setAckStore(store);
        this.poller.setTransmitter(transmitter.issuer());
        String jti = queue("session-1");
        transmitter.queueSet("broken", "not-a-set");
        // the transmitter goes away between handling the SETs and the request that
        // acknowledges them
        this.afterHandling = () -> transmitter.setAvailable(false);

        assertThatIllegalStateException().isThrownBy(this.poller::pollNow).withMessageContaining("503");

        assertThat(this.handled).containsExactly(jti);
        assertThat(transmitter.acknowledgedSets()).isEmpty();
        assertThat(store.pending(transmitter.issuer(), 10)).extracting(SsfPendingAck::jti)
            .containsExactly(jti, "broken");
        assertThat(this.poller.getPendingAckCount()).isEqualTo(2);

        transmitter.setAvailable(true);
        this.afterHandling = () -> {
        };
        this.poller.pollNow();
        assertThat(transmitter.acknowledgedSets()).containsExactly(jti);
        assertThat(transmitter.reportedErrors()).containsOnlyKeys("broken");
        assertThat(store.size(transmitter.issuer())).isZero();
    }

    @Test
    void acknowledgementsAreSentInBatches() {
        this.poller.setMaxAckBatch(2);
        List<String> queued = List.of(queue("session-1"), queue("session-2"), queue("session-3"));

        assertThat(this.poller.pollNow()).isEqualTo(3);
        // the request after handling carried two, the third waits for the next poll
        assertThat(transmitter.acknowledgedSets()).containsExactly(queued.get(0), queued.get(1));
        assertThat(this.poller.getPendingAckCount()).isEqualTo(1);

        this.poller.pollNow();
        assertThat(transmitter.acknowledgedSets()).containsExactlyElementsOf(queued);
        assertThat(this.poller.getPendingAckCount()).isZero();
    }

    @Test
    void pendingAcknowledgementsAreAGauge() {
        this.poller.setTransmitter(transmitter.issuer());
        this.poller.setInitialDelay(Duration.ofHours(1));
        this.poller.start();
        this.poller.stop();
        assertThat(this.meterRegistry.get("easyssf.receiver.poll.pending-acks")
            .tag("transmitter", transmitter.issuer())
            .gauge()
            .value()).isZero();
        String jti = queue("session-1");
        this.afterHandling = () -> transmitter.setAvailable(false);
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.handled).containsExactly(jti);
        assertThat(this.meterRegistry.get("easyssf.receiver.poll.pending-acks").gauge().value()).isEqualTo(1);
    }

    @Test
    void stopFlushesThePendingAcknowledgements() {
        this.poller.setInitialDelay(Duration.ofHours(1));
        String jti = queue("session-1");
        this.afterHandling = () -> transmitter.setAvailable(false);
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.poller.getPendingAckCount()).isEqualTo(1);
        transmitter.setAvailable(true);

        this.poller.start();
        this.poller.stop();

        assertThat(transmitter.acknowledgedSets()).containsExactly(jti);
        assertThat(this.poller.getPendingAckCount()).isZero();
    }

    @Test
    void longPollIsHeldUntilASetArrives() throws Exception {
        transmitter.setLongPollHold(Duration.ofSeconds(5));
        this.poller.setLongPolling(Duration.ofSeconds(5));
        this.poller.setInterval(Duration.ofSeconds(30));
        this.poller.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() >= 1);
        assertThat(transmitter.lastPollRequest()).containsEntry("returnImmediately", false);
        assertThat(this.handled).isEmpty();

        long queuedAt = System.nanoTime();
        String jti = queue("session-1");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(this.handled).containsExactly(jti));
        // handled while the request was held, long before the hold time elapsed
        assertThat(Duration.ofNanos(System.nanoTime() - queuedAt)).isLessThan(Duration.ofSeconds(3));
        // the next request went out right away and carries the acknowledgement
        await().atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> assertThat(transmitter.acknowledgedSets()).containsExactly(jti));
        assertThat(transmitter.lastPollRequest()).containsEntry("returnImmediately", false);
        this.poller.stop();
    }

    @Test
    void longPollAgainstATransmitterThatAnswersAtOnceWaitsTheInterval() {
        // the test transmitter does not hold by default
        this.poller.setLongPolling(Duration.ofSeconds(5));
        this.poller.setInterval(Duration.ofMinutes(5));
        this.poller.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() >= 1);
        await().during(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(transmitter.pollRequests()).isEqualTo(1));
        this.poller.stop();
    }

    @Test
    void stopInterruptsAHeldLongPoll() {
        transmitter.setLongPollHold(Duration.ofSeconds(30));
        this.poller.setLongPolling(Duration.ofSeconds(30));
        this.poller.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.pollRequests() >= 1);
        long stoppedAt = System.nanoTime();
        this.poller.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - stoppedAt)).isLessThan(Duration.ofSeconds(5));
        assertThat(this.poller.isRunning()).isFalse();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(pollerThreads()).isEmpty());
    }

    @Test
    void pollNowAsksForAnImmediateAnswerEvenWhenLongPolling() {
        transmitter.setLongPollHold(Duration.ofSeconds(30));
        this.poller.setLongPolling(Duration.ofSeconds(30));
        long started = System.nanoTime();
        assertThat(this.poller.pollNow()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(transmitter.lastPollRequest()).containsEntry("returnImmediately", true);
    }

    private String queue(String sessionId) {
        var claims = transmitter.setClaims("CaepSessionRevoked", opaque(sessionId)).build();
        transmitter.queueSet(transmitter.signSet(claims));
        return claims.getJWTID();
    }

    private double counter(String name, String... tags) {
        return this.meterRegistry.get(name).tags(tags).counter().count();
    }

}

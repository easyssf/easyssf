package org.easyssf.receiver.poll;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.easyssf.receiver.TestTransmitter;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.MicrometerSsfReceiverMetrics;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfPollerTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final List<String> handled = new ArrayList<>();

    private final List<RuntimeException> failures = new ArrayList<>();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SsfPoller poller;

    @BeforeEach
    void setUp() {
        transmitter.reset();
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
    void reportsFailedPollRequest() {
        SsfPoller unauthenticated = poller(() -> URI.create(transmitter.pollUri()), "wrong-token");
        assertThatIllegalStateException().isThrownBy(unauthenticated::pollNow).withMessageContaining("401");
        assertThat(this.meterRegistry.get("easyssf.receiver.poll").tag("outcome", "failure").timer().count())
            .isEqualTo(1);
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

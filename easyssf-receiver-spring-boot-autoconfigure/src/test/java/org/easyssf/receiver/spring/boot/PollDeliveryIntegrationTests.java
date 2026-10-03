package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.spring.boot.web.SsfPushEndpoint;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jwt.JWTClaimsSet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * A resource server that registers its own stream at the transmitter, polls it for events
 * and records metrics.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "easyssf.receiver.jdbc.enabled=false",
        "easyssf.receiver.http.use-rest-client=false", "easyssf.receiver.delivery-method=poll",
        "easyssf.receiver.stream.management=receiver", "easyssf.receiver.stream.events-requested=CaepSessionRevoked",
        "easyssf.receiver.stream.description=poll integration test", "easyssf.receiver.poll.interval=100ms",
        "easyssf.receiver.poll.initial-delay=0s", "easyssf.receiver.oauth2.client-id=" + TestTransmitter.CLIENT_ID,
        "easyssf.receiver.oauth2.client-secret=" + TestTransmitter.CLIENT_SECRET,
        "easyssf.receiver.oauth2.scopes=ssf.read,ssf.manage" })
class PollDeliveryIntegrationTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SsfReceiverStream receiverStream;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
        registry.add("easyssf.receiver.oauth2.token-uri", transmitter::tokenUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", transmitter::jwksUri);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void registersPollStreamOnStartup() {
        awaitStream();
        assertThat(transmitter.streams()).hasSize(1);
        Map<String, Object> stream = transmitter.streams().get(0);
        assertThat(stream).containsEntry("description", "poll integration test")
            .containsEntry("events_requested", List.of(SsfEventTypes.CAEP_SESSION_REVOKED));
        assertThat(stream.get("delivery"))
            .isEqualTo(Map.of("method", SsfDeliveryMethod.POLL.uri(), "endpoint_url", transmitter.pollUri()));
    }

    @Test
    void polledSessionRevokedEventInvalidatesAccessTokens() throws Exception {
        awaitStream();
        String accessToken = transmitter.accessToken("alice", "session-1", Instant.now());
        assertThat(api(accessToken)).isEqualTo(200);

        JWTClaimsSet set = transmitter
            .setClaims("CaepSessionRevoked", complex(issSub(transmitter.issuer(), "alice"), opaque("session-1")))
            .audience(this.receiverStream.getAudience())
            .build();
        transmitter.queueSet(transmitter.signSet(set));

        await().atMost(Duration.ofSeconds(5)).until(() -> api(accessToken) == 401);
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(set.getJWTID()));
        assertThat(this.meterRegistry.get("easyssf.receiver.sets")
            .tags("delivery", "poll", "outcome", "handled")
            .counter()
            .count()).isGreaterThanOrEqualTo(1);
        assertThat(
                this.meterRegistry.get("easyssf.receiver.events").tags("event", "CaepSessionRevoked").counter().count())
            .isGreaterThanOrEqualTo(1);
        assertThat(this.meterRegistry.get("easyssf.receiver.poll").tag("outcome", "success").timer().count())
            .isPositive();
    }

    @Test
    void rejectsPolledSetOfAnotherStream() {
        awaitStream();
        JWTClaimsSet set = transmitter.setClaims("CaepSessionRevoked", opaque("session-2"))
            .audience("another-receiver/another-stream")
            .build();
        transmitter.queueSet(transmitter.signSet(set));

        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.reportedErrors().containsKey(set.getJWTID()));
        assertThat(transmitter.reportedErrors().get(set.getJWTID()))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .containsEntry("err", "invalid_audience");
        assertThat(transmitter.acknowledgedSets()).doesNotContain(set.getJWTID());
    }

    @Test
    void doesNotExposePushEndpoint() throws Exception {
        assertThat(this.context.getBeansOfType(SsfPoller.class)).hasSize(1);
        assertThat(this.context.getBeansOfType(SsfPushEndpoint.class)).isEmpty();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/ssf/push"))
            .POST(HttpRequest.BodyPublishers.ofString("x"))
            .build();
        assertThat(this.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private void awaitStream() {
        await().atMost(Duration.ofSeconds(5)).until(() -> this.receiverStream.getStreamId() != null);
    }

    private int api(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/me"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        return this.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ResourceServerIntegrationTests.MeController.class)
    static class PollingResourceServerApplication {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

    }

}

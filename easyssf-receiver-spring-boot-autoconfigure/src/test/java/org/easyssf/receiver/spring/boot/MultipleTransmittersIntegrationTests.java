package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.spring.boot.health.SsfReceiverHealthIndicator;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jwt.JWTClaimsSet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * A receiver with two transmitters: the default one, configured at easyssf.receiver.*,
 * delivers by PUSH; a second one, configured by name, is polled.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = { "easyssf.receiver.jdbc.enabled=false", "easyssf.receiver.http.use-rest-client=false",
                // the default transmitter: push, authenticated by a header
                "easyssf.receiver.expected-audience=" + TestTransmitter.AUDIENCE,
                "easyssf.receiver.push.expected-auth-header=Bearer keycloak-secret",
                // a second transmitter, polled
                "easyssf.receiver.transmitters.okta.delivery-method=poll",
                "easyssf.receiver.transmitters.okta.stream.management=receiver",
                "easyssf.receiver.transmitters.okta.stream.events-requested=CaepSessionRevoked",
                "easyssf.receiver.transmitters.okta.poll.interval=100ms",
                "easyssf.receiver.transmitters.okta.poll.initial-delay=0s",
                "easyssf.receiver.transmitters.okta.oauth2.client-id=" + TestTransmitter.CLIENT_ID,
                "easyssf.receiver.transmitters.okta.oauth2.client-secret=" + TestTransmitter.CLIENT_SECRET,
                "easyssf.receiver.transmitters.okta.oauth2.scopes=ssf.read,ssf.manage" })
class MultipleTransmittersIntegrationTests {

    private static final TestTransmitter keycloak = new TestTransmitter();

    private static final TestTransmitter okta = new TestTransmitter();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SsfTransmitters transmitters;

    @Autowired
    private SsfReceiverHealthIndicator health;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private List<String> handledSets;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", keycloak::issuer);
        registry.add("easyssf.receiver.transmitters.okta.transmitter-issuer", okta::issuer);
        registry.add("easyssf.receiver.transmitters.okta.oauth2.token-uri", okta::tokenUri);
    }

    @AfterAll
    static void stopTransmitters() {
        keycloak.close();
        okta.close();
    }

    @Test
    void knowsBothTransmittersAndKeepsTheBeansOfTheDefaultOne() {
        assertThat(this.transmitters.all()).extracting(SsfTransmitter::getName).containsExactly("default", "okta");
        assertThat(this.transmitters.primary()).get()
            .extracting(SsfTransmitter::getIssuer)
            .isEqualTo(keycloak.issuer());
        assertThat(this.transmitters.get("okta")).get().extracting(SsfTransmitter::getPoller).isNotNull();
        assertThat(this.transmitters.get("default")).get().extracting(SsfTransmitter::getPoller).isNull();
        // the beans belong to the default transmitter
        assertThat(this.context.getBean(SsfStreamClient.class))
            .isSameAs(this.transmitters.get("default").orElseThrow().getStreamClient());
    }

    @Test
    void pushedSetsAreVerifiedByTheirTransmitter() throws Exception {
        assertThat(push("Bearer keycloak-secret", set(keycloak)).statusCode()).isEqualTo(202);
        assertThat(push("Bearer wrong", set(keycloak)).statusCode()).isEqualTo(401);
        // the polled transmitter expects no header; its SETs are still verified by its
        // keys and
        // against the audience of its stream
        List<String> oktaAudience = awaitOktaStream().getAudience();
        JWTClaimsSet oktaSet = okta.setClaims("CaepSessionRevoked", opaque("session-1")).audience(oktaAudience).build();
        assertThat(push(null, okta.signSet(oktaSet)).statusCode()).isEqualTo(202);
        assertThat(push(null, set(okta)).statusCode()).isEqualTo(400);
        TestTransmitter stranger = new TestTransmitter();
        try {
            HttpResponse<String> response = push(null, set(stranger));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("invalid_issuer");
        }
        finally {
            stranger.close();
        }
    }

    @Test
    void polledTransmitterDeliversItsEvents() {
        JWTClaimsSet set = okta.setClaims("CaepSessionRevoked", opaque("session-okta"))
            .audience(awaitOktaStream().getAudience())
            .build();
        okta.queueSet(okta.signSet(set));
        await().atMost(Duration.ofSeconds(5)).until(() -> okta.acknowledgedSets().contains(set.getJWTID()));
        assertThat(this.handledSets).contains(okta.issuer() + " session-okta");
        assertThat(this.meterRegistry.get("easyssf.receiver.sets")
            .tags("transmitter", "okta", "delivery", "poll", "outcome", "handled")
            .counter()
            .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void healthListsEveryTransmitter() {
        await().atMost(Duration.ofSeconds(5))
            .until(() -> this.transmitters.get("okta").orElseThrow().getPoller().getLastSuccessfulPollAt() != null);
        Health health = this.health.health();
        assertThat(health.getDetails()).containsOnlyKeys("default", "okta");
        @SuppressWarnings("unchecked")
        Map<String, Object> oktaDetails = (Map<String, Object>) health.getDetails().get("okta");
        assertThat(oktaDetails).containsEntry("delivery", "poll")
            .containsEntry("streamRegistration", "registered")
            .containsEntry("status", Status.UP.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultDetails = (Map<String, Object>) health.getDetails().get("default");
        assertThat(defaultDetails).containsEntry("delivery", "push").containsEntry("transmitter", keycloak.issuer());
    }

    private SsfReceiverStream awaitOktaStream() {
        SsfReceiverStream stream = this.transmitters.get("okta").orElseThrow().getReceiverStream();
        await().atMost(Duration.ofSeconds(5)).until(() -> stream.getStreamId() != null);
        return stream;
    }

    private HttpResponse<String> push(String authorization, String encodedSet) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/ssf/push"))
            .header("Content-Type", "application/secevent+jwt")
            .POST(HttpRequest.BodyPublishers.ofString(encodedSet));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String set(TestTransmitter transmitter) {
        JWTClaimsSet claims = transmitter.setClaims("CaepSessionRevoked", opaque("session-1"))
            .audience(TestTransmitter.AUDIENCE)
            .build();
        return transmitter.signSet(claims);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TwoTransmittersApplication {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        List<String> handledSets() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        SsfEventHandler recordingHandler(List<String> handledSets) {
            return (eventContext) -> handledSets
                .add(eventContext.eventToken().iss() + " " + eventContext.subjectFor("CaepSessionRevoked").opaqueId());
        }

    }

}

package org.easyssf.receiver.spring.boot;

import java.time.Duration;

import org.easyssf.receiver.spring.boot.health.SsfReceiverHealthIndicator;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The health indicator of a receiver that manages its stream and polls the transmitter.
 */
@SpringBootTest(properties = { "easyssf.receiver.jdbc.enabled=false", "easyssf.receiver.http.use-rest-client=false",
        "easyssf.receiver.delivery-method=poll", "easyssf.receiver.stream.management=receiver",
        "easyssf.receiver.stream.events-requested=CaepSessionRevoked", "easyssf.receiver.poll.interval=100ms",
        "easyssf.receiver.poll.initial-delay=0s", "easyssf.receiver.oauth2.client-id=" + TestTransmitter.CLIENT_ID,
        "easyssf.receiver.oauth2.client-secret=" + TestTransmitter.CLIENT_SECRET,
        "easyssf.receiver.oauth2.scopes=ssf.read,ssf.manage" })
class HealthPollIntegrationTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SsfReceiverHealthIndicator health;

    @Autowired
    private SsfReceiverStream receiverStream;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
        registry.add("easyssf.receiver.oauth2.token-uri", transmitter::tokenUri);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void isRegisteredAsHealthIndicator() {
        assertThat(this.context.getBean("easyssfHealthIndicator")).isInstanceOf(HealthIndicator.class)
            .isSameAs(this.health);
    }

    @Test
    void reportsUpOncePolling() {
        awaitStatus(Status.UP);
        Health health = this.health.health();
        assertThat(health.getDetails()).containsEntry("transmitter", transmitter.issuer())
            .containsEntry("delivery", "poll")
            .containsEntry("metadata", "resolved")
            .containsEntry("stream", this.receiverStream.getStreamId())
            .containsEntry("streamRegistration", "registered")
            .containsKeys("lastPoll", "lastSuccessfulPoll")
            .doesNotContainKeys("pollError", "streamRegistrationError");
    }

    @Test
    void reportsDownWhileTransmitterIsUnavailable() {
        awaitStatus(Status.UP);
        transmitter.setAvailable(false);
        try {
            awaitStatus(Status.DOWN);
            assertThat(this.health.health().getDetails()).containsKey("pollError").containsKey("lastSuccessfulPoll");
        }
        finally {
            transmitter.setAvailable(true);
        }
        awaitStatus(Status.UP);
    }

    private void awaitStatus(Status status) {
        await().atMost(Duration.ofSeconds(5)).until(() -> this.health.health().getStatus().equals(status));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PollingApplication {

    }

}

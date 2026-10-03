package org.easyssf.receiver.spring.boot;

import java.nio.charset.StandardCharsets;

import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.spring.boot.health.SsfReceiverHealthIndicator;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jwt.JWTClaimsSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * The health indicator of a receiver with PUSH delivery: unknown until the first SET
 * arrives, as the transmitter is only contacted then.
 */
@SpringBootTest(properties = { "easyssf.receiver.jdbc.enabled=false", "easyssf.receiver.http.use-rest-client=false",
        "easyssf.receiver.expected-audience=https://receiver.example" })
class HealthPushIntegrationTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    @Autowired
    private SsfReceiverHealthIndicator health;

    @Autowired
    private SsfPushHandler pushHandler;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void reportsUnknownUntilTheFirstSetAndUpAfterwards() {
        Health before = this.health.health();
        assertThat(before.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(before.getDetails()).containsEntry("delivery", "push")
            .containsEntry("metadata", "not retrieved yet")
            .containsEntry("stream", "none")
            .doesNotContainKeys("streamRegistration", "lastPoll");

        JWTClaimsSet set = transmitter.setClaims("CaepSessionRevoked", opaque("session-1"))
            .audience("https://receiver.example")
            .build();
        assertThat(this.pushHandler.handle(null, transmitter.signSet(set).getBytes(StandardCharsets.UTF_8)).status())
            .isEqualTo(202);

        Health after = this.health.health();
        assertThat(after.getStatus()).isEqualTo(Status.UP);
        assertThat(after.getDetails()).containsEntry("metadata", "resolved")
            .containsEntry("jwksUri", transmitter.jwksUri());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PushApplication {

    }

}

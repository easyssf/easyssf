package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.push.SsfPushResponse;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * Which audience the verifier {@link SsfTransmitterFactory} builds requires, and what a
 * push delivery gets back while the stream that tells the audience is not registered yet.
 */
class SsfTransmitterFactoryAudienceTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final SsfHttpClient http = new JdkSsfHttpClient();

    private final SsfReceiverStream receiverStream = new SsfReceiverStream();

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void refusesToVerifyAnythingWithoutAKnownAudience() {
        SsfTransmitterProperties properties = properties();
        assertThatExceptionOfType(InvalidConfigurationPropertyValueException.class)
            .isThrownBy(() -> verifier(properties))
            .withMessageContaining("easyssf.receiver.expected-audience")
            .withMessageContaining("easyssf.receiver.set-validation.allow-any-audience");
    }

    @Test
    void acceptsAnyAudienceOnlyWhenAllowed() {
        SsfTransmitterProperties properties = properties();
        properties.getSetValidation().setAllowAnyAudience(true);
        NimbusSsfSetVerifier verifier = verifier(properties);
        assertThat(verifier.verify(set("https://other.example")).aud()).containsExactly("https://other.example");
    }

    @Test
    void pushIsAnsweredWith503UntilTheStreamTellsTheAudienceAndCheckedAgainstItAfterwards() {
        SsfTransmitterProperties properties = properties();
        properties.getStream().setId("stream-1");
        SsfPushHandler push = new SsfPushHandler(new SsfSetProcessor(verifier(properties), null, List.of()),
                (String) null);

        // the stream is not registered yet: the SET is left to the redelivery
        SsfPushResponse response = push.handle(null, set(TestTransmitter.AUDIENCE).getBytes(StandardCharsets.UTF_8));
        assertThat(response.status()).isEqualTo(503);

        // the stream is known: SETs for other receivers are rejected, the stream's are
        // accepted
        this.receiverStream.setConfiguration(
                new SsfStreamConfiguration(Map.of("stream_id", "stream-1", "aud", List.of(TestTransmitter.AUDIENCE))));
        response = push.handle(null, set("https://other.example").getBytes(StandardCharsets.UTF_8));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains(SsfSetVerificationException.INVALID_AUDIENCE);
        response = push.handle(null, set(TestTransmitter.AUDIENCE).getBytes(StandardCharsets.UTF_8));
        assertThat(response.status()).isEqualTo(202);
    }

    @Test
    void streamWithoutAudienceAcceptsAnyAudienceOnlyWhenAllowed() {
        SsfTransmitterProperties properties = properties();
        properties.getStream().setId("stream-1");
        NimbusSsfSetVerifier verifier = verifier(properties);
        assertThatExceptionOfType(SsfTransmitterUnavailableException.class)
            .isThrownBy(() -> verifier.verify(set("https://other.example")));
        this.receiverStream.setConfiguration(new SsfStreamConfiguration(Map.of("stream_id", "stream-1")));
        assertThatExceptionOfType(SsfSetVerificationException.class)
            .isThrownBy(() -> verifier.verify(set("https://other.example")))
            .satisfies((ex) -> assertThat(ex.getErrorCode()).isEqualTo(SsfSetVerificationException.INVALID_AUDIENCE))
            .withMessageContaining("easyssf.receiver.set-validation.allow-any-audience");

        properties.getSetValidation().setAllowAnyAudience(true);
        NimbusSsfSetVerifier lenient = verifier(properties);
        assertThat(lenient.verify(set("https://other.example")).aud()).containsExactly("https://other.example");
    }

    private NimbusSsfSetVerifier verifier(SsfTransmitterProperties properties) {
        SsfTransmitterMetadataResolver metadataResolver = SsfTransmitterFactory
            .metadataResolver(SsfTransmitter.DEFAULT_NAME, properties, this.http);
        return SsfTransmitterFactory.verifier(SsfTransmitter.DEFAULT_NAME, properties, this.http, metadataResolver,
                this.receiverStream);
    }

    private static SsfTransmitterProperties properties() {
        SsfTransmitterProperties properties = new SsfTransmitterProperties();
        properties.setTransmitterIssuer(transmitter.issuer());
        properties.setTransmitterJwksUrl(URI.create(transmitter.jwksUri()));
        return properties;
    }

    private static String set(String audience) {
        return transmitter
            .signSet(transmitter.setClaims("CaepSessionRevoked", opaque("session-1")).audience(audience).build());
    }

}

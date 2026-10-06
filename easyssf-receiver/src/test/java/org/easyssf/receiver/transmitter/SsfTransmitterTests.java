package org.easyssf.receiver.transmitter;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * The parts of a transmitter working together: the registrar registers the stream in the
 * background and the poller fetches right after, not an interval later.
 */
class SsfTransmitterTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final SsfHttpClient http = new JdkSsfHttpClient();

    private final List<String> handled = new ArrayList<>();

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void pollerFetchesAsSoonAsTheStreamIsRegistered() {
        transmitter.reset();
        transmitter.setAvailable(true);
        SsfReceiverStream receiverStream = new SsfReceiverStream();
        SsfTransmitterMetadataResolver metadataResolver = new SsfTransmitterMetadataResolver(transmitter.issuer(), null,
                this.http);
        SsfTransmitterTokenProvider tokenProvider = () -> TestTransmitter.ACCESS_TOKEN;
        SsfStreamClient streamClient = new SsfStreamClient(this.http, tokenProvider, metadataResolver);
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(streamClient, receiverStream,
                SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(), transmitter::jwksUri, this.http);
        SsfEventHandler handler = (eventContext) -> this.handled.add(eventContext.eventToken().jti());
        SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(10), List.of(handler));
        SsfPoller poller = new SsfPoller(this.http, tokenProvider,
                () -> receiverStream.getConfiguration().map(SsfStreamConfiguration::deliveryEndpointUrl).orElse(null),
                processor);
        // only the wake-up of the registration can make the poller fetch within the test
        poller.setInterval(Duration.ofHours(1));
        poller.setEndpointRetry(Duration.ofHours(1));
        SsfTransmitter ssfTransmitter = SsfTransmitter.builder("test", transmitter.issuer())
            .metadataResolver(metadataResolver)
            .verifier(verifier)
            .tokenProvider(tokenProvider)
            .streamClient(streamClient)
            .receiverStream(receiverStream)
            .streamRegistrar(registrar)
            .poller(poller, true)
            .build();
        var claims = transmitter.setClaims("CaepSessionRevoked", opaque("session-1")).build();
        transmitter.queueSet(transmitter.signSet(claims));

        ssfTransmitter.start();
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(registrar.getState()).isEqualTo(SsfStreamRegistrar.State.REGISTERED);
                assertThat(this.handled).containsExactly(claims.getJWTID());
            });
            assertThat(URI.create(transmitter.pollUri()))
                .isEqualTo(receiverStream.getConfiguration().orElseThrow().deliveryEndpointUrl());
        }
        finally {
            ssfTransmitter.stop();
        }
    }

}

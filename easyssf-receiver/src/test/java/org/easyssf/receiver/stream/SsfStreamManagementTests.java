package org.easyssf.receiver.stream;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

class SsfStreamManagementTests {

    private static final URI PUSH_ENDPOINT = URI.create("https://receiver.example/ssf/push");

    private static final TestTransmitter transmitter = new TestTransmitter();

    private static final SsfHttpClient http = new JdkSsfHttpClient();

    private final SsfReceiverStream receiverStream = new SsfReceiverStream();

    private ClientCredentialsSsfTransmitterTokenProvider tokenProvider;

    private SsfStreamClient streamClient;

    @BeforeEach
    void setUp() {
        transmitter.reset();
        this.tokenProvider = new ClientCredentialsSsfTransmitterTokenProvider(http, URI.create(transmitter.tokenUri()),
                TestTransmitter.CLIENT_ID, TestTransmitter.CLIENT_SECRET);
        this.streamClient = streamClient(this.tokenProvider.getAccessToken());
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    private static SsfStreamClient streamClient(String accessToken) {
        return new SsfStreamClient(http, () -> accessToken,
                new SsfTransmitterMetadataResolver(transmitter.issuer(), null, http));
    }

    @Test
    void tokenProviderCachesAccessToken() {
        int requests = transmitter.tokenRequests();
        assertThat(this.tokenProvider.getAccessToken()).isEqualTo(TestTransmitter.ACCESS_TOKEN);
        assertThat(this.tokenProvider.getAccessToken()).isEqualTo(TestTransmitter.ACCESS_TOKEN);
        assertThat(transmitter.tokenRequests()).isEqualTo(requests);
    }

    @Test
    void tokenProviderAuthenticatesWithRequestBody() {
        ClientCredentialsSsfTransmitterTokenProvider provider = new ClientCredentialsSsfTransmitterTokenProvider(http,
                URI.create(transmitter.tokenUri()), TestTransmitter.CLIENT_ID, TestTransmitter.CLIENT_SECRET);
        provider.setAuthenticateWithRequestBody(true);
        provider.setScopes(List.of("ssf.read", "ssf.manage"));
        assertThat(provider.getAccessToken()).isEqualTo(TestTransmitter.ACCESS_TOKEN);
    }

    @Test
    void tokenProviderReportsRejectedCredentials() {
        ClientCredentialsSsfTransmitterTokenProvider provider = new ClientCredentialsSsfTransmitterTokenProvider(http,
                URI.create(transmitter.tokenUri()), TestTransmitter.CLIENT_ID, "wrong");
        assertThatExceptionOfType(SsfTransmitterUnavailableException.class).isThrownBy(provider::getAccessToken);
    }

    @Test
    void managesStream() {
        assertThat(this.streamClient.getStreams()).isEmpty();

        SsfStreamConfiguration created = this.streamClient.createStream(
                SsfStreamConfiguration.push(PUSH_ENDPOINT, "Bearer secret", List.of("CaepSessionRevoked"), "test"));
        assertThat(created.streamId()).isNotBlank();
        assertThat(created.issuer()).isEqualTo(transmitter.issuer());
        assertThat(created.audience()).containsExactly(TestTransmitter.CLIENT_ID + "/" + created.streamId());
        assertThat(created.deliveryMethod()).isEqualTo(SsfDeliveryMethod.PUSH.uri());
        assertThat(created.deliveryEndpointUrl()).isEqualTo(PUSH_ENDPOINT);
        assertThat(created.eventsRequested()).containsExactly(SsfEventTypes.CAEP_SESSION_REVOKED);
        assertThat(created.description()).isEqualTo("test");

        assertThat(this.streamClient.getStreams()).extracting(SsfStreamConfiguration::streamId)
            .containsExactly(created.streamId());
        assertThat(this.streamClient.getStream(created.streamId()).deliveryEndpointUrl()).isEqualTo(PUSH_ENDPOINT);

        SsfStreamConfiguration updated = this.streamClient.updateStream(created.streamId(),
                Map.of("events_requested", List.of(SsfEventTypes.CAEP_CREDENTIAL_CHANGE)));
        assertThat(updated.eventsRequested()).containsExactly(SsfEventTypes.CAEP_CREDENTIAL_CHANGE);
        assertThat(updated.deliveryEndpointUrl()).isEqualTo(PUSH_ENDPOINT);

        assertThat(this.streamClient.getStatus(created.streamId()).status()).isEqualTo(SsfStreamStatus.ENABLED);
        assertThat(this.streamClient.updateStatus(created.streamId(), SsfStreamStatus.PAUSED, "maintenance").status())
            .isEqualTo(SsfStreamStatus.PAUSED);

        this.streamClient.requestVerification(created.streamId(), "state-1");
        assertThat(transmitter.verificationRequests()).containsExactly("state-1");

        Map<String, Object> subject = Map.of("format", "email", "email", "alice@example.com");
        this.streamClient.addSubject(created.streamId(), subject, true);
        this.streamClient.removeSubject(created.streamId(), subject);
        assertThat(transmitter.addedSubjects()).containsExactly(subject);
        assertThat(transmitter.removedSubjects()).containsExactly(subject);

        this.streamClient.deleteStream(created.streamId());
        assertThat(this.streamClient.getStreams()).isEmpty();
    }

    @Test
    void reportsStatusOfFailedCalls() {
        assertThatExceptionOfType(SsfStreamException.class).isThrownBy(() -> streamClient("wrong-token").getStreams())
            .satisfies((ex) -> assertThat(ex.getStatusCode()).isEqualTo(401));
        assertThatExceptionOfType(SsfStreamException.class).isThrownBy(() -> this.streamClient.getStream("unknown"))
            .satisfies((ex) -> assertThat(ex.getStatusCode()).isEqualTo(404));
        assertThatExceptionOfType(SsfStreamException.class)
            .isThrownBy(() -> this.streamClient.updateStatus("unknown", SsfStreamStatus.PAUSED, null))
            .satisfies((ex) -> assertThat(ex.getStatusCode()).isEqualTo(404));
    }

    @Test
    void refusesStreamCreatedWithAnotherIssuer() {
        transmitter.setStreamIssuer("https://other.example");
        assertThatExceptionOfType(SsfStreamIssuerMismatchException.class).isThrownBy(
                () -> this.streamClient.createStream(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null)))
            .satisfies((ex) -> assertThat(ex.getStream().streamId())
                .isEqualTo(transmitter.streams().get(0).get("stream_id")))
            .withMessageContaining("https://other.example");
    }

    @Test
    void registrarDeletesStreamCreatedWithAnotherIssuerAndGivesUp() {
        transmitter.setStreamIssuer("https://other.example");
        SsfStreamRegistrar registrar = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setInitialRetryDelay(Duration.ofMillis(50));
        registrar.start();
        try {
            await().atMost(Duration.ofSeconds(5))
                .until(() -> transmitter.tokenRequests() > 0 && transmitter.streams().isEmpty());
            await().during(Duration.ofMillis(300)).until(() -> transmitter.streams().isEmpty());
        }
        finally {
            registrar.stop();
        }
        assertThat(this.receiverStream.getStreamId()).isNull();
    }

    @Test
    void obtainsNewAccessTokenWhenTheTransmitterRejectsTheCurrentOne() {
        SsfStreamClient client = new SsfStreamClient(http, this.tokenProvider,
                new SsfTransmitterMetadataResolver(transmitter.issuer(), null, http));
        assertThat(client.getStreams()).isEmpty();
        int tokenRequests = transmitter.tokenRequests();

        transmitter.expireAccessTokens();

        assertThat(client.getStreams()).isEmpty();
        assertThat(transmitter.tokenRequests()).isEqualTo(tokenRequests + 1);
    }

    @Test
    void registrarCreatesStream() {
        SsfStreamConfiguration stream = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null))
            .register();
        assertThat(transmitter.streams()).hasSize(1);
        assertThat(stream.deliveryEndpointUrl()).hasToString(transmitter.pollUri());
        assertThat(this.receiverStream.getStreamId()).isEqualTo(stream.streamId());
        assertThat(this.receiverStream.getAudience())
            .containsExactly(TestTransmitter.CLIENT_ID + "/" + stream.streamId());
    }

    @Test
    void registrarTellsItsListenersOnceTheStreamIsRegistered() {
        List<SsfStreamConfiguration> registered = new ArrayList<>();
        SsfStreamRegistrar registrar = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.addListener((stream) -> {
            throw new IllegalStateException("a failing listener does not keep the others from being told");
        });
        registrar.addListener(registered::add);
        SsfStreamConfiguration stream = registrar.register();
        assertThat(registered).containsExactly(stream);
        assertThat(this.receiverStream.getStreamId()).isEqualTo(stream.streamId());
    }

    @Test
    void registrarReusesExistingStream() {
        SsfStreamConfiguration desired = SsfStreamConfiguration.push(PUSH_ENDPOINT, null,
                List.of("CaepSessionRevoked", "CaepCredentialChange"), null);
        String streamId = registrar(desired).register().streamId();
        assertThat(registrar(desired).register().streamId()).isEqualTo(streamId);
        assertThat(transmitter.streams()).hasSize(1);
    }

    @Test
    void registrarUpdatesRequestedEventsOfExistingStream() {
        String streamId = registrar(
                SsfStreamConfiguration.push(PUSH_ENDPOINT, null, List.of("CaepSessionRevoked"), null))
            .register()
            .streamId();
        SsfStreamConfiguration stream = registrar(SsfStreamConfiguration.push(PUSH_ENDPOINT, null,
                List.of("CaepSessionRevoked", "CaepCredentialChange"), null))
            .register();
        assertThat(stream.streamId()).isEqualTo(streamId);
        assertThat(stream.eventsRequested()).containsExactlyInAnyOrder(SsfEventTypes.CAEP_SESSION_REVOKED,
                SsfEventTypes.CAEP_CREDENTIAL_CHANGE);
    }

    @Test
    void registrarChangesDeliveryOfTheOnlyStreamTheTransmitterAllows() {
        String streamId = registrar(
                SsfStreamConfiguration.push(PUSH_ENDPOINT, null, List.of("CaepSessionRevoked"), null))
            .register()
            .streamId();
        SsfStreamConfiguration stream = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null))
            .register();
        assertThat(stream.streamId()).isEqualTo(streamId);
        assertThat(stream.deliveryMethod()).isEqualTo(SsfDeliveryMethod.POLL.uri());
        assertThat(stream.deliveryEndpointUrl()).hasToString(transmitter.pollUri());
        assertThat(transmitter.streams()).hasSize(1);
    }

    @Test
    void registrarLooksUpStreamCreatedAtTheTransmitter() {
        Map<String, Object> existing = transmitter
            .addStream(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null).claims());
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forExistingStream(this.streamClient, this.receiverStream,
                (String) existing.get("stream_id"));
        assertThat(registrar.register().deliveryEndpointUrl()).hasToString(transmitter.pollUri());
        assertThat(this.receiverStream.getStreamId()).isEqualTo(existing.get("stream_id"));
    }

    @Test
    void registrarRetriesInTheBackgroundUntilTheTransmitterAnswers() {
        transmitter.setAvailable(false);
        SsfStreamRegistrar registrar = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setInitialRetryDelay(Duration.ofMillis(50));
        registrar.start();
        try {
            await().during(Duration.ofMillis(200)).until(() -> this.receiverStream.getStreamId() == null);
            transmitter.setAvailable(true);
            await().atMost(Duration.ofSeconds(5)).until(() -> this.receiverStream.getStreamId() != null);
        }
        finally {
            registrar.stop();
        }
        assertThat(transmitter.streams()).hasSize(1);
    }

    @Test
    void registrarDeletesManagedStreamOnShutdownIfAsked() {
        SsfStreamRegistrar registrar = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setDeleteOnShutdown(true);
        registrar.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> this.receiverStream.getStreamId() != null);
        registrar.stop();
        assertThat(transmitter.streams()).isEmpty();
    }

    private SsfStreamRegistrar registrar(SsfStreamConfiguration desired) {
        return SsfStreamRegistrar.forManagedStream(this.streamClient, this.receiverStream, desired);
    }

}

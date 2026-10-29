package org.easyssf.receiver.stream;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void refusesExistingStreamsWithAnotherIssuer() {
        transmitter.setStreamIssuer("https://other.example");
        Map<String, Object> existing = transmitter
            .addStream(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null).claims());
        String streamId = (String) existing.get("stream_id");
        assertThatExceptionOfType(SsfStreamIssuerMismatchException.class)
            .isThrownBy(() -> this.streamClient.getStream(streamId));
        assertThatExceptionOfType(SsfStreamIssuerMismatchException.class)
            .isThrownBy(() -> this.streamClient.getStreams());
        assertThatExceptionOfType(SsfStreamIssuerMismatchException.class)
            .isThrownBy(() -> this.streamClient.updateStream(streamId, Map.of("description", "x")));
        assertThatExceptionOfType(SsfStreamIssuerMismatchException.class).isThrownBy(() -> this.streamClient
            .replaceStream(streamId, SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null)));
    }

    @Test
    void registrarGivesUpOnExistingStreamWithAnotherIssuerWithoutDeletingIt() {
        transmitter.setStreamIssuer("https://other.example");
        Map<String, Object> existing = transmitter
            .addStream(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null).claims());
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forExistingStream(this.streamClient, this.receiverStream,
                (String) existing.get("stream_id"));
        registrar.setInitialRetryDelay(Duration.ofMillis(50));
        registrar.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> registrar.getState() == SsfStreamRegistrar.State.FAILED);
        }
        finally {
            registrar.stop();
        }
        assertThat(registrar.getLastError()).contains("https://other.example");
        assertThat(this.receiverStream.getStreamId()).isNull();
        // the stream is not this registrar's to delete
        assertThat(transmitter.streams()).hasSize(1);
    }

    @Test
    void registrarStartIsIdempotentAndStopEndsTheRegistration() {
        transmitter.setAvailable(false);
        Set<Thread> callers = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        SsfHttpClient recording = (request) -> {
            callers.add(Thread.currentThread());
            calls.incrementAndGet();
            return http.execute(request);
        };
        SsfStreamClient client = new SsfStreamClient(recording, () -> TestTransmitter.ACCESS_TOKEN,
                new SsfTransmitterMetadataResolver(transmitter.issuer(), null, http));
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(client, this.receiverStream,
                SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setInitialRetryDelay(Duration.ofMillis(20));
        registrar.start();
        registrar.start();
        assertThat(registrar.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);
        // the retries all come from the one registration thread
        assertThat(callers).hasSize(1);

        registrar.stop();
        registrar.stop();
        assertThat(registrar.isRunning()).isFalse();
        int callsAtStop = calls.get();
        await().during(Duration.ofMillis(200)).until(() -> calls.get() == callsAtStop);
        assertThat(this.receiverStream.getStreamId()).isNull();
    }

    @Test
    void registrationThatCompletesAfterStopPublishesNothing() throws Exception {
        CountDownLatch streamsRequested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // an HTTP client that outlives the interrupt of stop(), as one that swallows
        // interrupts would, and completes the registration afterwards
        SsfHttpClient slow = (request) -> {
            if (request.uri().getPath().endsWith("/streams") && "GET".equals(request.method())) {
                streamsRequested.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    }
                    catch (InterruptedException ex) {
                        // keeps waiting, the interrupt is swallowed
                    }
                }
            }
            return http.execute(request);
        };
        SsfStreamClient client = new SsfStreamClient(slow, () -> TestTransmitter.ACCESS_TOKEN,
                new SsfTransmitterMetadataResolver(transmitter.issuer(), null, http));
        List<SsfStreamConfiguration> registered = new ArrayList<>();
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(client, this.receiverStream,
                SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setDeleteOnShutdown(true);
        registrar.addListener(registered::add);
        registrar.start();
        assertThat(streamsRequested.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopping = Thread.ofVirtual().start(registrar::stop);
        // stop() waits for the registration, which completes only once released
        await().during(Duration.ofMillis(200)).until(stopping::isAlive);
        release.countDown();
        stopping.join(Duration.ofSeconds(10));
        assertThat(stopping.isAlive()).isFalse();

        // the stream created on the way is deleted, as it would have been on shutdown
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.streams().isEmpty());
        assertThat(registered).isEmpty();
        assertThat(this.receiverStream.getStreamId()).isNull();
        assertThat(registrar.getState()).isNotEqualTo(SsfStreamRegistrar.State.REGISTERED);
    }

    @Test
    void stopWaitsForAPublicationInProgressAndDeletesTheStreamItPublished() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<SsfStreamConfiguration> registered = new ArrayList<>();
        SsfStreamRegistrar registrar = registrar(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setDeleteOnShutdown(true);
        registrar.addListener((stream) -> {
            // the registration passed its check and is publishing when stop() is called
            registered.add(stream);
            publishing.countDown();
            try {
                release.await();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        registrar.start();
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopping = Thread.ofVirtual().start(registrar::stop);
        await().during(Duration.ofMillis(200)).until(stopping::isAlive);
        assertThat(transmitter.streams()).hasSize(1);
        release.countDown();
        stopping.join(Duration.ofSeconds(10));
        assertThat(stopping.isAlive()).isFalse();

        // the publication completed before stop() went on, so the stream it published is
        // the one stop() deleted
        assertThat(registered).hasSize(1);
        assertThat(this.receiverStream.getStreamId()).isEqualTo(registered.get(0).streamId());
        assertThat(transmitter.streams()).isEmpty();
    }

    @Test
    void registrationThatOutlivesItsStopLeavesTheStreamToTheRegistrationThatFollowed() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // the transmitter created the stream, but the client does not return, and does
        // not
        // care for the interrupt of stop() either
        SsfHttpClient stuck = (request) -> {
            var response = http.execute(request);
            if ("POST".equals(request.method()) && request.uri().getPath().endsWith("/streams")) {
                created.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    }
                    catch (InterruptedException ex) {
                        // keeps waiting
                    }
                }
            }
            return response;
        };
        SsfStreamClient client = new SsfStreamClient(stuck, () -> TestTransmitter.ACCESS_TOKEN,
                new SsfTransmitterMetadataResolver(transmitter.issuer(), null, http));
        List<SsfStreamConfiguration> registered = new ArrayList<>();
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(client, this.receiverStream,
                SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), null));
        registrar.setDeleteOnShutdown(true);
        registrar.addListener(registered::add);
        registrar.start();
        assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
        String streamId = (String) transmitter.streams().get(0).get("stream_id");

        // stop() gives the first registration up after its timeout, start() begins a
        // second
        // one, which finds the stream the first created and adopts it
        long stopping = System.nanoTime();
        registrar.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - stopping))
            .isGreaterThanOrEqualTo(SsfStreamRegistrar.STOP_TIMEOUT);
        registrar.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> this.receiverStream.getStreamId() != null);
        assertThat(this.receiverStream.getStreamId()).isEqualTo(streamId);

        // the first registration returns at last: it neither publishes nor deletes the
        // stream the second one uses
        release.countDown();
        await().during(Duration.ofMillis(300)).until(() -> transmitter.streams().size() == 1);
        assertThat(registered).hasSize(1);
        assertThat(registrar.getState()).isEqualTo(SsfStreamRegistrar.State.REGISTERED);

        registrar.stop();
        assertThat(transmitter.streams()).isEmpty();
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

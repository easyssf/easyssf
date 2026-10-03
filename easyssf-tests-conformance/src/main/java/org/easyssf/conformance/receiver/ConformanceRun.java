package org.easyssf.conformance.receiver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.push.SsfPushResponse;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamIssuerMismatchException;
import org.easyssf.receiver.stream.SsfStreamVerification;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One test run: a receiver assembled from the easyssf receiver library that creates its
 * stream at the emulated transmitter, plays the operations of its
 * {@link ConformanceScenario}, accepts the events the transmitter delivers and deletes
 * the stream once no more arrive.
 *
 * <p>
 * Every run has its own receiver (keys, access token, de-duplication), since every test
 * of the suite emulates a new transmitter under the same issuer.
 */
public class ConformanceRun implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ConformanceRun.class);

    public enum Status {

        STARTING, RUNNING, FINISHED, REFUSED_STREAM, FAILED, STOPPED

    }

    public record LogEntry(Instant time, String message) {
    }

    public record ReceivedSet(Instant time, String jti, List<String> events, String outcome) {
    }

    private final String id;

    private final ConformanceScenario scenario;

    private final String issuer;

    private final SsfDeliveryMethod deliveryMethod;

    private final CtsProperties properties;

    private final SsfTransmitterMetadataResolver metadataResolver;

    private final SsfStreamClient streamClient;

    private final RecordingStreamVerification verification;

    private final NimbusSsfSetVerifier verifier;

    private final SsfSetProcessor processor;

    private final SsfPushHandler pushHandler;

    private final SsfPoller poller;

    private final String pushAuthorizationHeader;

    private final Instant startedAt = Instant.now();

    private final List<LogEntry> log = new CopyOnWriteArrayList<>();

    private final List<ReceivedSet> receivedSets = new CopyOnWriteArrayList<>();

    private final AtomicInteger verifiedCount = new AtomicInteger();

    private volatile Status status = Status.STARTING;

    private volatile SsfStreamConfiguration stream;

    private volatile Instant lastActivity = Instant.now();

    private volatile boolean streamDeleted;

    private volatile boolean stopRequested;

    private volatile Duration idleTimeout;

    private volatile Thread thread;

    /**
     * @param issuer the issuer of the emulated transmitter, i.e. the test instance of the
     * suite
     * @param deliveryMethod how the transmitter is to deliver the events
     */
    public ConformanceRun(String id, ConformanceScenario scenario, String issuer, SsfDeliveryMethod deliveryMethod,
            CtsProperties properties, SsfHttpClient httpClient) {
        this.id = id;
        this.scenario = scenario;
        this.issuer = issuer;
        this.deliveryMethod = deliveryMethod;
        this.properties = properties;
        this.idleTimeout = properties.getRun().getIdleTimeout();
        this.metadataResolver = new SsfTransmitterMetadataResolver(issuer, null, httpClient);
        SsfTransmitterTokenProvider tokenProvider = tokenProvider(properties.getAuth(), httpClient,
                this.metadataResolver);
        this.streamClient = new SsfStreamClient(httpClient, tokenProvider, this.metadataResolver);
        this.verification = new RecordingStreamVerification();
        this.verification.setStreamId(this::streamId);
        this.verifier = new NimbusSsfSetVerifier(issuer, () -> this.metadataResolver.resolve().jwksUri().toString(),
                httpClient);
        this.verifier.setExpectedAudiences(() -> (this.stream != null) ? this.stream.audience() : List.of());
        this.processor = new SsfSetProcessor(new RecordingVerifier(), new InMemorySsfJtiDedupStore(10_000),
                List.of(this::recordHandledSet));
        this.processor.setStreamVerification(this.verification);
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        this.pushAuthorizationHeader = "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        this.pushHandler = new SsfPushHandler(this.processor, this.pushAuthorizationHeader);
        if (deliveryMethod == SsfDeliveryMethod.POLL) {
            this.poller = new SsfPoller(httpClient, tokenProvider,
                    () -> (this.stream != null && !this.streamDeleted) ? this.stream.deliveryEndpointUrl() : null,
                    this.processor);
            this.poller.setInterval(properties.getDelivery().getPollInterval());
            this.poller.setInitialDelay(Duration.ZERO);
        }
        else {
            this.poller = null;
        }
    }

    private static SsfTransmitterTokenProvider tokenProvider(CtsProperties.Auth auth, SsfHttpClient httpClient,
            SsfTransmitterMetadataResolver metadataResolver) {
        if (auth.getMode() == CtsProperties.Auth.Mode.STATIC) {
            String accessToken = auth.getAccessToken();
            return () -> accessToken;
        }
        // the token endpoint of the emulated authorization server lives next to the
        // transmitter;
        // it is looked up with the first call, so that a run can start while the suite is
        // down
        return new SsfTransmitterTokenProvider() {

            private volatile ClientCredentialsSsfTransmitterTokenProvider delegate;

            @Override
            public String getAccessToken() {
                ClientCredentialsSsfTransmitterTokenProvider delegate = this.delegate;
                if (delegate == null) {
                    URI tokenUri = URI.create(metadataResolver.resolve().issuer() + "/token");
                    delegate = new ClientCredentialsSsfTransmitterTokenProvider(httpClient, tokenUri,
                            auth.getClientId(), auth.getClientSecret());
                    delegate.setScopes(auth.getScopes());
                    delegate.setAuthenticateWithRequestBody(
                            auth.getClientAuthenticationMethod() == CtsProperties.Auth.ClientAuthenticationMethod.POST);
                    this.delegate = delegate;
                }
                return delegate.getAccessToken();
            }

            @Override
            public void invalidate() {
                ClientCredentialsSsfTransmitterTokenProvider delegate = this.delegate;
                if (delegate != null) {
                    delegate.invalidate();
                }
            }

        };
    }

    public String getId() {
        return this.id;
    }

    public ConformanceScenario getScenario() {
        return this.scenario;
    }

    public String getIssuer() {
        return this.issuer;
    }

    public SsfDeliveryMethod getDeliveryMethod() {
        return this.deliveryMethod;
    }

    public Status getStatus() {
        return this.status;
    }

    public Instant getStartedAt() {
        return this.startedAt;
    }

    public String streamId() {
        SsfStreamConfiguration stream = this.stream;
        return (stream != null) ? stream.streamId() : null;
    }

    public List<LogEntry> getLog() {
        return List.copyOf(this.log);
    }

    public List<ReceivedSet> getReceivedSets() {
        return List.copyOf(this.receivedSets);
    }

    public boolean isActive() {
        return this.status == Status.STARTING || this.status == Status.RUNNING;
    }

    /**
     * Overrides {@code cts.run.idle-timeout} for this run, to be called before
     * {@link #start()}.
     */
    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public void start() {
        this.thread = Thread.ofVirtual().name("cts-run-" + this.id).start(this);
    }

    /**
     * Ends the run: the stream is deleted.
     */
    public void stop() {
        this.stopRequested = true;
        Thread thread = this.thread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Handles a SET the transmitter pushed to this run.
     */
    public SsfPushResponse push(String authorizationHeader, byte[] body) {
        this.lastActivity = Instant.now();
        return this.pushHandler.handle(authorizationHeader, body);
    }

    @Override
    public void run() {
        this.status = Status.RUNNING;
        try {
            execute();
            if (this.status == Status.RUNNING) {
                this.status = Status.FINISHED;
            }
        }
        catch (InterruptedException ex) {
            log("Run stopped");
            this.status = Status.STOPPED;
        }
        catch (RuntimeException ex) {
            log("Run failed: " + ex.getMessage());
            logger.error("Conformance run " + this.id + " failed", ex);
            this.status = Status.FAILED;
        }
        finally {
            if (this.poller != null) {
                this.poller.stop();
            }
            deleteStream();
        }
    }

    private void execute() throws InterruptedException {
        log("Scenario " + this.scenario.alias() + " with " + this.deliveryMethod + " delivery against " + this.issuer);
        SsfTransmitterMetadata metadata = this.metadataResolver.resolve();
        log("Transmitter metadata: jwks_uri " + metadata.jwksUri() + ", configuration_endpoint "
                + metadata.configurationEndpoint());

        SsfStreamConfiguration desired = desiredStream("easyssf conformance run " + this.id);
        try {
            this.stream = this.streamClient.createStream(desired);
        }
        catch (SsfStreamIssuerMismatchException ex) {
            log("Refusing the stream: " + ex.getMessage());
            this.stream = ex.getStream();
            deleteStream();
            this.status = Status.REFUSED_STREAM;
            return;
        }
        log("Created stream " + streamId() + ": audience " + this.stream.audience() + ", delivery "
                + this.stream.deliveryMethod() + " " + this.stream.deliveryEndpointUrl() + ", events delivered "
                + aliases(this.stream.eventsDelivered()));
        if (this.poller != null) {
            this.poller.start();
        }
        if (this.scenario == ConformanceScenario.CREATE_DELETE) {
            return;
        }

        SsfStreamConfiguration read = this.streamClient.getStream(streamId());
        log("Read stream configuration: events requested " + aliases(read.eventsRequested()));
        SsfStreamStatus streamStatus = this.streamClient.getStatus(streamId());
        log("Read stream status: " + streamStatus.status());

        if (this.scenario == ConformanceScenario.STREAM_MANAGEMENT) {
            SsfStreamConfiguration updated = this.streamClient.updateStream(streamId(),
                    Map.of("description", desired.description() + " (updated)"));
            log("Updated stream: description '" + updated.description() + "'");
            SsfStreamConfiguration replaced = this.streamClient.replaceStream(streamId(),
                    desiredStream(desired.description() + " (replaced)"));
            log("Replaced stream: description '" + replaced.description() + "'");
        }

        verifyStream();

        if (this.scenario == ConformanceScenario.STATUS_UPDATE) {
            log("Pausing the stream: "
                    + this.streamClient.updateStatus(streamId(), SsfStreamStatus.PAUSED, "conformance run").status());
            Thread.sleep(Duration.ofSeconds(3));
            log("Enabling the stream: "
                    + this.streamClient.updateStatus(streamId(), SsfStreamStatus.ENABLED, "conformance run").status());
        }
        if (this.scenario == ConformanceScenario.REMOVE_SUBJECT) {
            Map<String, Object> subject = this.properties.getStream().getSubjectToRemove();
            this.streamClient.removeSubject(streamId(), subject);
            log("Removed subject " + subject + " from the stream");
        }

        Duration idleTimeout = this.idleTimeout;
        log("Accepting events until none arrived for " + idleTimeout.toSeconds() + "s");
        Instant phaseStart = Instant.now();
        while (!this.stopRequested) {
            Thread.sleep(500);
            Instant now = Instant.now();
            Instant latest = this.lastActivity.isAfter(phaseStart) ? this.lastActivity : phaseStart;
            if (Duration.between(latest, now).compareTo(idleTimeout) > 0) {
                log("No event for " + idleTimeout.toSeconds() + "s");
                break;
            }
            if (Duration.between(this.startedAt, now).compareTo(this.properties.getRun().getMaxDuration()) > 0) {
                log("Maximum run duration reached");
                break;
            }
        }
    }

    private void verifyStream() throws InterruptedException {
        Duration timeout = this.properties.getRun().getVerificationTimeout();
        for (int attempt = 1; attempt <= 3; attempt++) {
            int verifiedBefore = this.verifiedCount.get();
            int rejectedBefore = this.verification.rejected.get();
            String state = this.verification.requestVerification(this.streamClient, streamId());
            log("Requested stream verification with state " + state);
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline) && !this.stopRequested) {
                if (this.verifiedCount.get() > verifiedBefore) {
                    return;
                }
                if (this.verification.rejected.get() > rejectedBefore) {
                    log("Rejected a verification event, requesting the verification again");
                    break;
                }
                Thread.sleep(200);
            }
            if (this.verification.rejected.get() == rejectedBefore) {
                log("No verification event within " + timeout.toSeconds() + "s, going on without it");
                return;
            }
        }
    }

    private SsfStreamConfiguration desiredStream(String description) {
        List<String> events = this.properties.getStream().getEventsRequested();
        if (this.deliveryMethod == SsfDeliveryMethod.POLL) {
            return SsfStreamConfiguration.poll(events, description);
        }
        return SsfStreamConfiguration.push(this.properties.getDelivery().getPushUrl(), this.pushAuthorizationHeader,
                events, description);
    }

    private void deleteStream() {
        String streamId = streamId();
        if (streamId == null || this.streamDeleted) {
            return;
        }
        this.streamDeleted = true;
        try {
            this.streamClient.deleteStream(streamId);
            log("Deleted stream " + streamId);
        }
        catch (RuntimeException ex) {
            log("Could not delete stream " + streamId + ": " + ex.getMessage());
        }
    }

    private void recordHandledSet(SsfEventContext eventContext) {
        SsfEventToken token = eventContext.eventToken();
        if (eventContext.hasEvent(SsfEventTypes.SSF_STREAM_VERIFICATION)) {
            Map<String, Object> event = eventContext.eventFor(SsfEventTypes.SSF_STREAM_VERIFICATION);
            String state = (event != null && event.get("state") != null) ? "state " + event.get("state")
                    : "no state (initiated by the transmitter)";
            log("Verification event " + token.jti() + " accepted, " + state);
            this.verifiedCount.incrementAndGet();
        }
        record(token.jti(), aliases(List.copyOf(token.events().keySet())), "accepted");
    }

    private void record(String jti, List<String> events, String outcome) {
        this.lastActivity = Instant.now();
        this.receivedSets.add(new ReceivedSet(this.lastActivity, jti, events, outcome));
        log("SET " + jti + " " + events + ": " + outcome);
    }

    private void log(String message) {
        this.log.add(new LogEntry(Instant.now(), message));
        logger.info("[run " + this.id + "] " + message);
    }

    private static List<String> aliases(List<String> eventTypes) {
        return eventTypes.stream().map(SsfEventTypes::aliasOf).toList();
    }

    /**
     * Records the SETs the verifier rejects.
     */
    private final class RecordingVerifier implements SsfSetVerifier {

        @Override
        public SsfEventToken verify(String encodedSet) {
            try {
                return ConformanceRun.this.verifier.verify(encodedSet);
            }
            catch (SsfSetVerificationException ex) {
                record(jtiOf(encodedSet), List.of(), "rejected with " + ex.getErrorCode() + ": " + ex.getMessage());
                throw ex;
            }
        }

        private String jtiOf(String encodedSet) {
            try {
                String payload = encodedSet.split("\\.")[1];
                String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
                Object jti = com.nimbusds.jose.util.JSONObjectUtils.parse(json).get("jti");
                return (jti != null) ? jti.toString() : "?";
            }
            catch (Exception ex) {
                return "?";
            }
        }

    }

    /**
     * Records the verification events it rejects.
     */
    private final class RecordingStreamVerification extends SsfStreamVerification {

        private final AtomicInteger rejected = new AtomicInteger();

        @Override
        public void validate(SsfEventContext eventContext) {
            try {
                super.validate(eventContext);
            }
            catch (SsfSetVerificationException ex) {
                this.rejected.incrementAndGet();
                record(eventContext.eventToken().jti(), List.of("SsfStreamVerification"),
                        "rejected with " + ex.getErrorCode() + ": " + ex.getMessage());
                throw ex;
            }
        }

    }

}

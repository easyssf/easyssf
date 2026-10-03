package org.easyssf.receiver.poll;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.event.SsfEventHandlingException;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Fetches SETs from the poll endpoint of the transmitter (RFC 8936).
 *
 * <p>
 * A SET is acknowledged once it was handled. An invalid SET is reported as an error to
 * the transmitter. A SET that could not be handled is neither acknowledged nor reported,
 * the transmitter delivers it again with a later poll.
 */
public class SsfPoller {

    private static final Logger logger = LoggerFactory.getLogger(SsfPoller.class);

    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);

    private static final int MAX_REQUESTS_PER_POLL = 100;

    private final SsfHttpClient httpClient;

    private final SsfTransmitterTokenProvider tokenProvider;

    private final Supplier<URI> endpoint;

    private final SsfSetProcessor processor;

    private final List<String> pendingAcks = new ArrayList<>();

    private final Map<String, Object> pendingErrors = new LinkedHashMap<>();

    private SsfReceiverMetrics metrics = SsfReceiverMetrics.NOOP;

    private Duration interval = Duration.ofSeconds(30);

    private Duration initialDelay = Duration.ZERO;

    private int maxEvents = 100;

    private volatile Instant pausedUntil = Instant.MIN;

    private volatile Instant lastPollAt;

    private volatile Instant lastSuccessfulPollAt;

    private volatile String lastPollError;

    private volatile ScheduledExecutorService scheduler;

    private final ReentrantLock polling = new ReentrantLock();

    /**
     * @param httpClient used to call the transmitter
     * @param tokenProvider provides the access token to authenticate with
     * @param endpoint supplies the poll endpoint, {@code null} as long as it is not known
     * @param processor processes the fetched SETs
     */
    public SsfPoller(SsfHttpClient httpClient, SsfTransmitterTokenProvider tokenProvider, Supplier<URI> endpoint,
            SsfSetProcessor processor) {
        SsfAssert.notNull(httpClient, "httpClient must not be null");
        SsfAssert.notNull(tokenProvider, "tokenProvider must not be null");
        SsfAssert.notNull(endpoint, "endpoint must not be null");
        SsfAssert.notNull(processor, "processor must not be null");
        this.httpClient = httpClient;
        this.tokenProvider = tokenProvider;
        this.endpoint = endpoint;
        this.processor = processor;
    }

    public void setMetrics(SsfReceiverMetrics metrics) {
        SsfAssert.notNull(metrics, "metrics must not be null");
        this.metrics = metrics;
    }

    public void setInterval(Duration interval) {
        SsfAssert.isTrue(interval != null && interval.isPositive(), "interval must be positive");
        this.interval = interval;
    }

    public void setInitialDelay(Duration initialDelay) {
        SsfAssert.isTrue(initialDelay != null && !initialDelay.isNegative(), "initialDelay must not be negative");
        this.initialDelay = initialDelay;
    }

    /**
     * Sets the maximum number of SETs fetched with one request.
     */
    public void setMaxEvents(int maxEvents) {
        SsfAssert.isTrue(maxEvents > 0, "maxEvents must be positive");
        this.maxEvents = maxEvents;
    }

    /**
     * Starts polling the transmitter periodically. Without it, SETs are only fetched by
     * calling {@link #pollNow()}.
     */
    public void start() {
        ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ssf-poller").daemon().factory());
        scheduler.scheduleWithFixedDelay(this::pollQuietly, this.initialDelay.toMillis(), this.interval.toMillis(),
                TimeUnit.MILLISECONDS);
        this.scheduler = scheduler;
    }

    /**
     * Stops polling the transmitter periodically.
     */
    public void stop() {
        ScheduledExecutorService scheduler = this.scheduler;
        this.scheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isRunning() {
        return this.scheduler != null;
    }

    /**
     * @return when the transmitter was last polled, {@code null} if it was not polled yet
     */
    public Instant getLastPollAt() {
        return this.lastPollAt;
    }

    /**
     * @return when the transmitter was last polled successfully, {@code null} if never
     */
    public Instant getLastSuccessfulPollAt() {
        return this.lastSuccessfulPollAt;
    }

    /**
     * @return why the last poll failed, {@code null} if it succeeded or none was made yet
     */
    public String getLastPollError() {
        return this.lastPollError;
    }

    /**
     * @return until when the transmitter asked not to be polled, in the past if it did
     * not
     */
    public Instant getPausedUntil() {
        return this.pausedUntil;
    }

    private void pollQuietly() {
        try {
            pollNow();
        }
        catch (RuntimeException ex) {
            logger.warn("Could not poll the SSF transmitter: " + ex.getMessage());
            logger.debug("Cause of the failed poll", ex);
        }
    }

    /**
     * Fetches and processes the SETs that are available at the transmitter and
     * acknowledges them. One poll runs at a time: a call while another is in progress
     * returns right away.
     * @return the number of SETs fetched, {@code 0} as well if the poll endpoint is not
     * known yet, the transmitter asked to slow down or a poll is in progress
     */
    public int pollNow() {
        if (!this.polling.tryLock()) {
            logger.debug("Not polling, a poll is in progress");
            return 0;
        }
        try {
            return poll();
        }
        finally {
            this.polling.unlock();
        }
    }

    private int poll() {
        URI endpoint = this.endpoint.get();
        if (endpoint == null) {
            logger.debug("Not polling, the poll endpoint of the SSF stream is not known yet");
            return 0;
        }
        if (Instant.now().isBefore(this.pausedUntil)) {
            logger.debug("Not polling, the SSF transmitter asked to wait until " + this.pausedUntil);
            return 0;
        }
        this.lastPollAt = Instant.now();
        try {
            int fetched = 0;
            boolean moreAvailable = true;
            for (int request = 0; moreAvailable && request < MAX_REQUESTS_PER_POLL; request++) {
                Map<String, Object> response = poll(endpoint, this.maxEvents);
                Map<String, Object> sets = sets(response);
                sets.forEach(this::process);
                fetched += sets.size();
                moreAvailable = Boolean.TRUE.equals(response.get("moreAvailable")) && !sets.isEmpty();
            }
            if (!this.pendingAcks.isEmpty() || !this.pendingErrors.isEmpty()) {
                // acknowledge right away instead of with the next poll
                Map<String, Object> sets = sets(poll(endpoint, 0));
                sets.forEach(this::process);
                fetched += sets.size();
            }
            this.lastSuccessfulPollAt = Instant.now();
            this.lastPollError = null;
            return fetched;
        }
        catch (RuntimeException ex) {
            this.lastPollError = ex.getMessage();
            throw ex;
        }
    }

    private void process(String jti, Object encodedSet) {
        try {
            this.processor.process(String.valueOf(encodedSet), SsfDeliveryMethod.POLL);
            this.pendingAcks.add(jti);
        }
        catch (SsfSetVerificationException ex) {
            logger.warn("Rejecting polled SET " + jti + ": " + ex.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("err", ex.getErrorCode());
            error.put("description", ex.getMessage());
            this.pendingErrors.put(jti, error);
        }
        catch (SsfTransmitterUnavailableException | SsfEventHandlingException ex) {
            // not acknowledged, the transmitter delivers the SET again
            logger.warn("Could not process polled SET " + jti + ": " + ex.getMessage());
        }
    }

    private Map<String, Object> poll(URI endpoint, int maxEvents) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("maxEvents", maxEvents);
        request.put("returnImmediately", true);
        List<String> acks = List.copyOf(this.pendingAcks);
        Map<String, Object> errors = Map.copyOf(this.pendingErrors);
        if (!acks.isEmpty()) {
            request.put("ack", acks);
        }
        if (!errors.isEmpty()) {
            request.put("setErrs", errors);
        }
        long started = System.nanoTime();
        SsfHttpResponse httpResponse;
        Map<String, Object> response;
        try {
            httpResponse = send(endpoint, request);
            if (httpResponse.status() == 401) {
                // the access token expired or was revoked: try once more with a new one
                this.tokenProvider.invalidate();
                httpResponse = send(endpoint, request);
            }
            String body = httpResponse.body();
            response = (httpResponse.isSuccessful() && body != null && !body.isBlank()) ? JSONObjectUtils.parse(body)
                    : Map.of();
        }
        catch (Exception ex) {
            this.metrics.pollCompleted(Duration.ofNanos(System.nanoTime() - started), false);
            throw new IllegalStateException("Poll request to " + endpoint + " failed: " + ex.getMessage(), ex);
        }
        if (!httpResponse.isSuccessful()) {
            this.metrics.pollCompleted(Duration.ofNanos(System.nanoTime() - started), false);
            pauseIfAsked(httpResponse);
            throw new IllegalStateException(
                    "Poll request to " + endpoint + " failed with status " + httpResponse.status());
        }
        this.pendingAcks.removeAll(acks);
        errors.keySet().forEach(this.pendingErrors::remove);
        this.metrics.pollCompleted(Duration.ofNanos(System.nanoTime() - started), true);
        return response;
    }

    private SsfHttpResponse send(URI endpoint, Map<String, Object> request) throws java.io.IOException {
        return this.httpClient.execute(SsfHttpRequest.of("POST", endpoint)
            .withHeader("Accept", "application/json")
            .withBearerToken(this.tokenProvider.getAccessToken())
            .withJsonBody(JSONObjectUtils.toJSONString(request)));
    }

    private void pauseIfAsked(SsfHttpResponse response) {
        int status = response.status();
        String retryAfter = response.header("Retry-After");
        if ((status == 429 || status == 503) && retryAfter != null) {
            try {
                Duration pause = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                this.pausedUntil = Instant.now().plus((pause.compareTo(MAX_RETRY_AFTER) < 0) ? pause : MAX_RETRY_AFTER);
            }
            catch (NumberFormatException invalid) {
                // a date instead of seconds: fall back to the regular interval
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sets(Map<String, Object> response) {
        return (response.get("sets") instanceof Map<?, ?> sets) ? (Map<String, Object>) sets : Map.of();
    }

}

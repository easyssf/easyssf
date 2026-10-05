package org.easyssf.receiver.poll;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
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
 * the transmitter delivers it again with a later poll. Acknowledgements and error reports
 * ride on the next poll request (section 2.4) and wait in the {@link SsfPollAckStore}
 * until that request succeeded; with the in-memory store of the default they are lost
 * when the application stops, with a durable store they survive a restart.
 *
 * <p>
 * By default the poller polls at a fixed {@link #setInterval(Duration) interval} and asks
 * the transmitter to answer right away ({@code returnImmediately: true}, short polling).
 * With {@link #setLongPolling(Duration) long polling} it keeps one request outstanding
 * instead: the transmitter holds the request until SETs are available or its hold time
 * elapses (section 2.5), and the poller sends the next request as soon as it has handled
 * the response. A backlog is fetched with immediate requests in both modes.
 */
public class SsfPoller {

    private static final Logger logger = LoggerFactory.getLogger(SsfPoller.class);

    private static final Duration DEFAULT_MAX_PAUSE = Duration.ofMinutes(5);

    private static final int MAX_REQUESTS_PER_POLL = 100;

    /**
     * How much longer than the hold time of the transmitter a long poll waits for the
     * response.
     */
    private static final Duration LONG_POLL_MARGIN = Duration.ofSeconds(10);

    /**
     * A long poll that comes back empty faster than this was not held: the transmitter
     * does not support long polling, and the poller waits the interval before the next
     * request instead of asking again at once.
     */
    private static final Duration MIN_HOLD = Duration.ofSeconds(1);

    private final SsfHttpClient httpClient;

    private final SsfTransmitterTokenProvider tokenProvider;

    private final Supplier<URI> endpoint;

    private final SsfSetProcessor processor;

    private SsfPollAckStore ackStore = new InMemorySsfPollAckStore();

    private int maxAckBatch = 1000;

    private SsfReceiverMetrics metrics = SsfReceiverMetrics.NOOP;

    private Duration interval = Duration.ofSeconds(30);

    private Duration initialDelay = Duration.ZERO;

    private Duration longPollingHold;

    private boolean flushOnStop = true;

    private ThreadFactory threadFactory = Thread.ofPlatform().name("ssf-poller").daemon().factory();

    private int maxEvents = 100;

    private String transmitter;

    private Duration rateLimitFallback;

    private Duration maxPause = DEFAULT_MAX_PAUSE;

    private volatile Instant pausedUntil = Instant.MIN;

    private volatile Instant lastPollAt;

    private volatile Instant lastSuccessfulPollAt;

    private volatile String lastPollError;

    private volatile Thread thread;

    private volatile boolean running;

    private final ReentrantLock polling = new ReentrantLock();

    /** start() and stop() are idempotent and safe to call from any thread */
    private final ReentrantLock lifecycle = new ReentrantLock();

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

    /**
     * @param ackStore keeps the acknowledgements until a poll request carried them; an
     * {@link InMemorySsfPollAckStore} by default
     */
    public void setAckStore(SsfPollAckStore ackStore) {
        SsfAssert.notNull(ackStore, "ackStore must not be null");
        this.ackStore = ackStore;
    }

    /**
     * @param maxAckBatch the most acknowledgements and error reports one request carries,
     * the rest goes with the next one; 1000 by default
     */
    public void setMaxAckBatch(int maxAckBatch) {
        SsfAssert.isTrue(maxAckBatch > 0, "maxAckBatch must be positive");
        this.maxAckBatch = maxAckBatch;
    }

    /**
     * Sets the time between two polls. With long polling, the pause after a failed
     * request or after a transmitter answered an empty long poll at once.
     */
    public void setInterval(Duration interval) {
        SsfAssert.isTrue(interval != null && interval.isPositive(), "interval must be positive");
        this.interval = interval;
    }

    public void setInitialDelay(Duration initialDelay) {
        SsfAssert.isTrue(initialDelay != null && !initialDelay.isNegative(), "initialDelay must not be negative");
        this.initialDelay = initialDelay;
    }

    /**
     * Switches to long polling (RFC 8936, section 2.5): the first request of a poll asks
     * the transmitter to hold it until SETs are available
     * ({@code returnImmediately: false}), and a running poller sends the next request as
     * soon as a response was handled.
     * @param hold how long the transmitter holds a request, part of the agreement with it
     * (section 2.2); the request waits that long plus a margin for the response.
     * {@code null} returns to short polling.
     */
    public void setLongPolling(Duration hold) {
        SsfAssert.isTrue(hold == null || hold.isPositive(), "hold must be positive");
        this.longPollingHold = hold;
    }

    public boolean isLongPolling() {
        return this.longPollingHold != null;
    }

    /**
     * @param flushOnStop whether {@link #stop()} sends the pending acknowledgements with
     * a last request, so that the transmitter does not deliver the SETs again after a
     * restart; {@code true} by default
     */
    public void setFlushOnStop(boolean flushOnStop) {
        this.flushOnStop = flushOnStop;
    }

    /**
     * Sets what creates the thread {@link #start()} polls on, for a framework that
     * manages its threads, names them per transmitter or prefers virtual threads
     * ({@code Thread.ofVirtual().name("ssf-poller").factory()} works: the loop only
     * blocks in the HTTP call and in sleeps). By default a platform daemon thread named
     * {@code ssf-poller}. {@link #stop()} interrupts the thread, so the factory must not
     * hand out threads that swallow interrupts.
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        SsfAssert.notNull(threadFactory, "threadFactory must not be null");
        this.threadFactory = threadFactory;
    }

    /**
     * @param transmitter the issuer of the transmitter that is polled, for the metrics
     * and as the key of its acknowledgements in the {@link SsfPollAckStore}
     */
    public void setTransmitter(String transmitter) {
        this.transmitter = transmitter;
    }

    /**
     * @param rateLimitFallback how long to pause polling after a {@code 429 Too Many
     * Requests} without a {@code Retry-After} header; {@code null}, the default, to poll
     * again at the regular interval
     */
    public void setRateLimitFallback(Duration rateLimitFallback) {
        SsfAssert.isTrue(rateLimitFallback == null || !rateLimitFallback.isNegative(),
                "rateLimitFallback must not be negative");
        this.rateLimitFallback = rateLimitFallback;
    }

    /**
     * @param maxPause the longest pause a {@code Retry-After} header or the rate limit
     * fallback can cause, 5 minutes by default
     */
    public void setMaxPause(Duration maxPause) {
        SsfAssert.isTrue(maxPause != null && maxPause.isPositive(), "maxPause must be positive");
        this.maxPause = maxPause;
    }

    /**
     * Sets the maximum number of SETs fetched with one request.
     */
    public void setMaxEvents(int maxEvents) {
        SsfAssert.isTrue(maxEvents > 0, "maxEvents must be positive");
        this.maxEvents = maxEvents;
    }

    /**
     * Starts polling the transmitter on a thread of its own (see
     * {@link #setThreadFactory(ThreadFactory)}): periodically, or with one request always
     * outstanding when long polling. Without it, SETs are only fetched by calling
     * {@link #pollNow()}. Calling it again while running has no effect.
     */
    public void start() {
        this.lifecycle.lock();
        try {
            if (this.thread != null) {
                return;
            }
            this.running = true;
            Thread thread = this.threadFactory.newThread(this::run);
            SsfAssert.notNull(thread, "the thread factory returned no thread");
            this.thread = thread;
            thread.start();
        }
        finally {
            this.lifecycle.unlock();
        }
    }

    /**
     * Stops polling: ends the polling thread, interrupting an outstanding long poll, and
     * sends the pending acknowledgements with a last request unless
     * {@link #setFlushOnStop(boolean)} says otherwise.
     */
    public void stop() {
        this.lifecycle.lock();
        try {
            Thread thread = this.thread;
            this.thread = null;
            this.running = false;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(Duration.ofSeconds(5));
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (this.flushOnStop) {
                    flushAcks();
                }
            }
        }
        finally {
            this.lifecycle.unlock();
        }
    }

    public boolean isRunning() {
        return this.thread != null;
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

    /**
     * @return the number of acknowledgements and error reports waiting for the next poll
     * request
     */
    public int getPendingAckCount() {
        String key = ackKey(this.endpoint.get());
        return (key != null) ? this.ackStore.size(key) : 0;
    }

    private void run() {
        try {
            Thread.sleep(this.initialDelay);
            while (this.running) {
                Duration pause = this.interval;
                long started = System.nanoTime();
                try {
                    PollResult result = this.polling.tryLock() ? pollLocked() : PollResult.SKIPPED;
                    if (result.skipped()) {
                        pause = pauseWhileSkipped();
                    }
                    else if (isLongPolling()) {
                        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                        boolean held = result.fetched() > 0 || elapsed.compareTo(MIN_HOLD) >= 0;
                        pause = held ? Duration.ZERO : this.interval;
                    }
                }
                catch (RuntimeException ex) {
                    if (!this.running) {
                        return;
                    }
                    logger.warn("Could not poll the SSF transmitter: " + ex.getMessage());
                    logger.debug("Cause of the failed poll", ex);
                }
                if (this.running && pause.isPositive()) {
                    Thread.sleep(pause);
                }
            }
        }
        catch (InterruptedException ex) {
            // stop() ends the thread
        }
    }

    private PollResult pollLocked() {
        try {
            return poll();
        }
        finally {
            this.polling.unlock();
        }
    }

    private Duration pauseWhileSkipped() {
        Instant pausedUntil = this.pausedUntil;
        Duration untilResume = Duration.between(Instant.now(), pausedUntil);
        return (untilResume.isPositive() && untilResume.compareTo(this.interval) > 0) ? untilResume : this.interval;
    }

    /**
     * Fetches and processes the SETs that are available at the transmitter and
     * acknowledges them, with the request answered immediately whether or not long
     * polling is on. One poll runs at a time: a call while another is in progress returns
     * right away.
     * @return the number of SETs fetched, {@code 0} as well if the poll endpoint is not
     * known yet, the transmitter asked to slow down or a poll is in progress
     */
    public int pollNow() {
        if (!this.polling.tryLock()) {
            logger.debug("Not polling, a poll is in progress");
            return 0;
        }
        try {
            return poll(false).fetched();
        }
        finally {
            this.polling.unlock();
        }
    }

    private PollResult poll() {
        return poll(isLongPolling());
    }

    private PollResult poll(boolean hold) {
        URI endpoint = this.endpoint.get();
        if (endpoint == null) {
            logger.debug("Not polling, the poll endpoint of the SSF stream is not known yet");
            return PollResult.SKIPPED;
        }
        if (Instant.now().isBefore(this.pausedUntil)) {
            logger.debug("Not polling, the SSF transmitter asked to wait until " + this.pausedUntil);
            return PollResult.SKIPPED;
        }
        this.lastPollAt = Instant.now();
        String key = ackKey(endpoint);
        try {
            int fetched = 0;
            boolean moreAvailable = true;
            for (int request = 0; moreAvailable && request < MAX_REQUESTS_PER_POLL; request++) {
                // only the first request of a poll is held, a backlog is fetched right
                // away
                Map<String, Object> response = poll(endpoint, key, this.maxEvents, !(hold && request == 0));
                Map<String, Object> sets = sets(response);
                sets.forEach((jti, set) -> process(key, jti, set));
                fetched += sets.size();
                moreAvailable = Boolean.TRUE.equals(response.get("moreAvailable")) && !sets.isEmpty();
            }
            if (!hold && this.ackStore.size(key) > 0) {
                // acknowledge right away instead of with the next poll; a long poll sends
                // its next request at once anyway
                Map<String, Object> sets = sets(poll(endpoint, key, 0, true));
                sets.forEach((jti, set) -> process(key, jti, set));
                fetched += sets.size();
            }
            this.lastSuccessfulPollAt = Instant.now();
            this.lastPollError = null;
            return new PollResult(fetched, false);
        }
        catch (RuntimeException ex) {
            this.lastPollError = ex.getMessage();
            throw ex;
        }
    }

    private void process(String key, String jti, Object encodedSet) {
        try {
            this.processor.process(String.valueOf(encodedSet), SsfDeliveryMethod.POLL);
            this.ackStore.record(key, SsfPendingAck.ack(jti));
        }
        catch (SsfSetVerificationException ex) {
            logger.warn("Rejecting polled SET " + jti + ": " + ex.getMessage());
            this.ackStore.record(key, SsfPendingAck.error(jti, ex.getErrorCode(), ex.getMessage()));
        }
        catch (SsfTransmitterUnavailableException | SsfEventHandlingException ex) {
            // not acknowledged, the transmitter delivers the SET again
            logger.warn("Could not process polled SET " + jti + ": " + ex.getMessage());
        }
    }

    private Map<String, Object> poll(URI endpoint, String key, int maxEvents, boolean returnImmediately) {
        List<SsfPendingAck> pending = this.ackStore.pending(key, this.maxAckBatch);
        List<String> acks = new ArrayList<>();
        Map<String, Object> errors = new LinkedHashMap<>();
        for (SsfPendingAck ack : pending) {
            if (ack.isError()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("err", ack.errorCode());
                if (ack.errorDescription() != null) {
                    error.put("description", ack.errorDescription());
                }
                errors.put(ack.jti(), error);
            }
            else {
                acks.add(ack.jti());
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("maxEvents", maxEvents);
        request.put("returnImmediately", returnImmediately);
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
            httpResponse = send(endpoint, request, returnImmediately);
            if (httpResponse.status() == 401) {
                // the access token expired or was revoked: try once more with a new one
                this.tokenProvider.invalidate();
                httpResponse = send(endpoint, request, returnImmediately);
            }
            String body = httpResponse.body();
            response = (httpResponse.isSuccessful() && body != null && !body.isBlank()) ? JSONObjectUtils.parse(body)
                    : Map.of();
        }
        catch (Exception ex) {
            this.metrics.pollCompleted(this.transmitter, Duration.ofNanos(System.nanoTime() - started), false);
            throw new IllegalStateException("Poll request to " + endpoint + " failed: " + ex.getMessage(), ex);
        }
        if (!httpResponse.isSuccessful()) {
            this.metrics.pollCompleted(this.transmitter, Duration.ofNanos(System.nanoTime() - started), false);
            pauseIfAsked(httpResponse);
            throw new IllegalStateException(
                    "Poll request to " + endpoint + " failed with status " + httpResponse.status());
        }
        if (!pending.isEmpty()) {
            this.ackStore.remove(key, pending.stream().map(SsfPendingAck::jti).toList());
        }
        this.metrics.pollCompleted(this.transmitter, Duration.ofNanos(System.nanoTime() - started), true);
        return response;
    }

    private SsfHttpResponse send(URI endpoint, Map<String, Object> request, boolean returnImmediately)
            throws java.io.IOException {
        SsfHttpRequest httpRequest = SsfHttpRequest.of("POST", endpoint)
            .withHeader("Accept", "application/json")
            .withBearerToken(this.tokenProvider.getAccessToken())
            .withJsonBody(JSONObjectUtils.toJSONString(request));
        if (!returnImmediately && this.longPollingHold != null) {
            httpRequest = httpRequest.withTimeout(this.longPollingHold.plus(LONG_POLL_MARGIN));
        }
        return this.httpClient.execute(httpRequest);
    }

    /**
     * Sends the pending acknowledgements with a request that asks for no SETs, best
     * effort.
     */
    private void flushAcks() {
        URI endpoint = this.endpoint.get();
        String key = ackKey(endpoint);
        if (key == null || this.ackStore.size(key) == 0 || !this.polling.tryLock()) {
            return;
        }
        try {
            poll(endpoint, key, 0, true);
            logger.debug("Acknowledged the pending SETs before stopping");
        }
        catch (RuntimeException ex) {
            logger.debug("Could not acknowledge the pending SETs before stopping, they are sent with the next poll: "
                    + ex.getMessage());
        }
        finally {
            this.polling.unlock();
        }
    }

    private String ackKey(URI endpoint) {
        if (this.transmitter != null) {
            return this.transmitter;
        }
        return (endpoint != null) ? endpoint.toString() : null;
    }

    private void pauseIfAsked(SsfHttpResponse response) {
        int status = response.status();
        String retryAfter = response.header("Retry-After");
        Duration pause = null;
        if ((status == 429 || status == 503) && retryAfter != null) {
            try {
                pause = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
            }
            catch (NumberFormatException invalid) {
                // a date instead of seconds: treated like no header
            }
        }
        if (pause == null && status == 429) {
            pause = this.rateLimitFallback;
        }
        if (pause != null) {
            this.pausedUntil = Instant.now().plus((pause.compareTo(this.maxPause) < 0) ? pause : this.maxPause);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sets(Map<String, Object> response) {
        return (response.get("sets") instanceof Map<?, ?> sets) ? (Map<String, Object>) sets : Map.of();
    }

    private record PollResult(int fetched, boolean skipped) {

        static final PollResult SKIPPED = new PollResult(0, true);

    }

}

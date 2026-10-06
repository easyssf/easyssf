package org.easyssf.receiver.stream;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.support.SsfAssert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the stream of this receiver known after {@link #start()}, without holding the
 * startup of the application up: the transmitter is called in the background and retried
 * until it answers.
 *
 * <p>
 * Either looks up a stream that was created at the transmitter by its identifier, or
 * manages the stream itself: it reuses the stream the receiver already has at the
 * transmitter, brings it in line with the desired configuration, or creates it.
 *
 * <p>
 * {@link #addListener Listeners} learn when the stream is registered;
 * {@code SsfTransmitter} uses that to have the poller fetch right away instead of an
 * interval later.
 */
public class SsfStreamRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(SsfStreamRegistrar.class);

    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    private final SsfStreamClient streamClient;

    private final SsfReceiverStream receiverStream;

    private final String streamId;

    private final SsfStreamConfiguration desiredStream;

    private boolean deleteOnShutdown;

    private Duration initialRetryDelay = Duration.ofSeconds(1);

    private volatile Thread thread;

    private volatile State state = State.NOT_STARTED;

    private volatile String lastError;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Told when the stream was looked up or registered.
     */
    @FunctionalInterface
    public interface Listener {

        /**
         * The stream is registered: its configuration is known and the
         * {@code SsfReceiverStream} is set.
         * @param stream the stream as the transmitter has it
         */
        void streamRegistered(SsfStreamConfiguration stream);

    }

    /**
     * Where the registration stands.
     */
    public enum State {

        /** {@link SsfStreamRegistrar#start()} was not called yet. */
        NOT_STARTED,

        /**
         * The stream is being looked up or registered, with retries while the transmitter
         * cannot be reached.
         */
        REGISTERING,

        /** The stream is known and matches what the receiver wants. */
        REGISTERED,

        /**
         * The stream cannot be used, see {@link SsfStreamRegistrar#getLastError()}. Not
         * retried.
         */
        FAILED

    }

    /**
     * Looks up the stream with the given identifier.
     */
    public static SsfStreamRegistrar forExistingStream(SsfStreamClient streamClient, SsfReceiverStream receiverStream,
            String streamId) {
        SsfAssert.hasText(streamId, "streamId must not be empty");
        return new SsfStreamRegistrar(streamClient, receiverStream, streamId, null);
    }

    /**
     * Discovers, updates or creates the stream of the receiver.
     * @param desiredStream the stream the receiver wants to have
     * @see SsfStreamConfiguration#push
     * @see SsfStreamConfiguration#poll
     */
    public static SsfStreamRegistrar forManagedStream(SsfStreamClient streamClient, SsfReceiverStream receiverStream,
            SsfStreamConfiguration desiredStream) {
        SsfAssert.notNull(desiredStream, "desiredStream must not be null");
        return new SsfStreamRegistrar(streamClient, receiverStream, null, desiredStream);
    }

    private SsfStreamRegistrar(SsfStreamClient streamClient, SsfReceiverStream receiverStream, String streamId,
            SsfStreamConfiguration desiredStream) {
        SsfAssert.notNull(streamClient, "streamClient must not be null");
        SsfAssert.notNull(receiverStream, "receiverStream must not be null");
        this.streamClient = streamClient;
        this.receiverStream = receiverStream;
        this.streamId = streamId;
        this.desiredStream = desiredStream;
    }

    /**
     * Whether a stream this receiver manages is deleted at the transmitter when the
     * application stops.
     */
    public void setDeleteOnShutdown(boolean deleteOnShutdown) {
        this.deleteOnShutdown = deleteOnShutdown;
    }

    /**
     * Adds a listener that is told whenever {@link #register()} succeeded, on the thread
     * that registered the stream. A listener that throws is logged and does not keep the
     * stream from being used.
     */
    public void addListener(Listener listener) {
        SsfAssert.notNull(listener, "listener must not be null");
        this.listeners.add(listener);
    }

    public void setInitialRetryDelay(Duration initialRetryDelay) {
        SsfAssert.isTrue(initialRetryDelay != null && initialRetryDelay.isPositive(),
                "initialRetryDelay must be positive");
        this.initialRetryDelay = initialRetryDelay;
    }

    /**
     * Starts looking up or registering the stream in the background.
     */
    public void start() {
        this.state = State.REGISTERING;
        this.thread = Thread.ofVirtual().name("ssf-stream-registrar").start(this::registerWithRetries);
    }

    public State getState() {
        return this.state;
    }

    /**
     * @return why the last attempt to look up or register the stream failed, {@code null}
     * if it succeeded or none was made yet
     */
    public String getLastError() {
        return this.lastError;
    }

    /**
     * Stops the background registration and deletes the stream if asked to.
     * @see #setDeleteOnShutdown(boolean)
     */
    public void stop() {
        Thread thread = this.thread;
        this.thread = null;
        if (thread != null) {
            thread.interrupt();
        }
        String registeredStreamId = this.receiverStream.getStreamId();
        if (this.deleteOnShutdown && this.desiredStream != null && registeredStreamId != null) {
            try {
                this.streamClient.deleteStream(registeredStreamId);
                logger.info("Deleted SSF stream " + registeredStreamId);
            }
            catch (RuntimeException ex) {
                logger.warn("Could not delete SSF stream " + registeredStreamId + ": " + ex.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return this.thread != null;
    }

    private void registerWithRetries() {
        Duration delay = this.initialRetryDelay;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                register();
                return;
            }
            catch (SsfStreamIssuerMismatchException ex) {
                // trying again would only create another stream that must not be used
                logger.error("Not using the SSF stream: " + ex.getMessage());
                this.lastError = ex.getMessage();
                this.state = State.FAILED;
                deleteQuietly(ex.getStream().streamId());
                return;
            }
            catch (RuntimeException ex) {
                this.lastError = ex.getMessage();
                if (this.thread == null) {
                    return;
                }
                logger.warn("Could not set up the SSF stream, trying again in " + delay.toSeconds() + "s: "
                        + ex.getMessage());
                logger.debug("Cause of the failed SSF stream setup", ex);
            }
            try {
                Thread.sleep(delay);
            }
            catch (InterruptedException ex) {
                return;
            }
            delay = (delay.multipliedBy(2).compareTo(MAX_RETRY_DELAY) < 0) ? delay.multipliedBy(2) : MAX_RETRY_DELAY;
        }
    }

    /**
     * Looks up or registers the stream once.
     * @return the stream
     * @throws SsfStreamException if the transmitter could not be called
     */
    public SsfStreamConfiguration register() {
        SsfStreamConfiguration stream;
        String action;
        if (this.desiredStream == null) {
            stream = this.streamClient.getStream(this.streamId);
            action = "Using";
        }
        else {
            List<SsfStreamConfiguration> streams = this.streamClient.getStreams();
            SsfStreamConfiguration existing = streams.stream()
                .filter(this::hasDesiredDelivery)
                .findFirst()
                .orElse(null);
            if (existing != null && hasDesiredEvents(existing)) {
                stream = existing;
                action = "Using existing";
            }
            else if (existing != null) {
                stream = this.streamClient.updateStream(existing.streamId(), changes());
                action = "Updated";
            }
            else {
                try {
                    stream = this.streamClient.createStream(this.desiredStream);
                    action = "Created";
                }
                catch (SsfStreamException ex) {
                    // the transmitter allows a single stream per receiver and it is
                    // configured differently
                    if (ex.getStatusCode() != 409 || streams.size() != 1) {
                        throw ex;
                    }
                    stream = this.streamClient.updateStream(streams.get(0).streamId(), changes());
                    action = "Updated";
                }
            }
        }
        this.receiverStream.setConfiguration(stream);
        logger.info(action + " SSF stream " + stream.streamId() + " (delivery " + stream.deliveryMethod() + " "
                + Objects.toString(stream.deliveryEndpointUrl(), "") + ", events "
                + stream.eventsDelivered().stream().map(SsfEventTypes::aliasOf).toList() + ", audience "
                + stream.audience() + ")");
        this.lastError = null;
        this.state = State.REGISTERED;
        for (Listener listener : this.listeners) {
            try {
                listener.streamRegistered(stream);
            }
            catch (RuntimeException ex) {
                logger.warn("A listener failed on the registration of SSF stream " + stream.streamId() + ": " + ex);
                logger.debug("Cause of the failed listener", ex);
            }
        }
        return stream;
    }

    private void deleteQuietly(String streamId) {
        try {
            this.streamClient.deleteStream(streamId);
        }
        catch (RuntimeException ex) {
            logger.warn("Could not delete SSF stream " + streamId + ": " + ex.getMessage());
        }
    }

    private boolean hasDesiredEvents(SsfStreamConfiguration stream) {
        return eventTypes(stream.eventsRequested()).equals(eventTypes(this.desiredStream.eventsRequested()));
    }

    private boolean hasDesiredDelivery(SsfStreamConfiguration stream) {
        if (!Objects.equals(stream.deliveryMethod(), this.desiredStream.deliveryMethod())) {
            return false;
        }
        return SsfDeliveryMethod.POLL.uri().equals(stream.deliveryMethod())
                || Objects.equals(stream.deliveryEndpointUrl(), this.desiredStream.deliveryEndpointUrl());
    }

    private Map<String, Object> changes() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("delivery", this.desiredStream.claims().get("delivery"));
        changes.put("events_requested", this.desiredStream.eventsRequested());
        if (this.desiredStream.description() != null) {
            changes.put("description", this.desiredStream.description());
        }
        return changes;
    }

    private static Set<String> eventTypes(List<String> eventTypes) {
        Set<String> uris = new HashSet<>();
        eventTypes.forEach((eventType) -> uris.add(SsfEventTypes.resolve(eventType)));
        return uris;
    }

}

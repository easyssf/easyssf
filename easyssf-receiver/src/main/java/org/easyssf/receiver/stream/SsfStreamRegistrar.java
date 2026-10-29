package org.easyssf.receiver.stream;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * The thread of the current registration; a registration that finds another thread
     * here, or none, was stopped and publishes nothing
     */
    private volatile Thread thread;

    /** start() and stop() are idempotent and safe to call from any thread */
    private final ReentrantLock lifecycle = new ReentrantLock();

    /**
     * Guards {@link #thread} and the publication of a registration: stop() takes the
     * thread away under it, the registration checks that it is still the thread and
     * publishes under it, so a registration either publishes before stop() proceeds or
     * not at all. A lock of its own, as stop() must not hold it while it waits for the
     * registration thread.
     */
    private final ReentrantLock publication = new ReentrantLock();

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
     * Starts looking up or registering the stream in the background. Calling it again
     * while a registration runs has no effect.
     */
    public void start() {
        this.lifecycle.lock();
        try {
            if (this.thread != null) {
                return;
            }
            this.state = State.REGISTERING;
            Thread thread = Thread.ofVirtual().name("ssf-stream-registrar").unstarted(this::registerWithRetries);
            this.publication.lock();
            try {
                this.thread = thread;
            }
            finally {
                this.publication.unlock();
            }
            thread.start();
        }
        finally {
            this.lifecycle.unlock();
        }
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
        this.lifecycle.lock();
        try {
            Thread thread;
            // a publication in progress completes first, so that the stream it publishes
            // is the one deleted below; none starts afterwards
            this.publication.lock();
            try {
                thread = this.thread;
                this.thread = null;
            }
            finally {
                this.publication.unlock();
            }
            if (thread != null) {
                thread.interrupt();
                try {
                    // a registration in flight ends with the interrupted call; one that
                    // completes regardless sees it is no longer current and publishes
                    // nothing
                    if (!thread.join(Duration.ofSeconds(5))) {
                        logger.warn("The registration of the SSF stream is still in progress");
                    }
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
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
        finally {
            this.lifecycle.unlock();
        }
    }

    public boolean isRunning() {
        return this.thread != null;
    }

    private void registerWithRetries() {
        Thread thread = Thread.currentThread();
        Duration delay = this.initialRetryDelay;
        while (!thread.isInterrupted()) {
            try {
                Registration registration = lookUp();
                this.publication.lock();
                try {
                    if (this.thread != thread) {
                        // stopped while the transmitter was called: the result is not
                        // published, and a stream created on the way is not left behind
                        if (registration.created() && this.deleteOnShutdown) {
                            deleteQuietly(registration.stream().streamId());
                        }
                        return;
                    }
                    publish(registration);
                }
                finally {
                    this.publication.unlock();
                }
                return;
            }
            catch (SsfStreamIssuerMismatchException ex) {
                // trying again would only find or create the same stream
                logger.error("Not using the SSF stream: " + ex.getMessage());
                this.lastError = ex.getMessage();
                this.state = State.FAILED;
                return;
            }
            catch (RuntimeException ex) {
                this.lastError = ex.getMessage();
                if (this.thread != thread) {
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
     * Looks up or registers the stream once, on the calling thread, and publishes it.
     * @return the stream
     * @throws SsfStreamException if the transmitter could not be called
     * @throws SsfStreamIssuerMismatchException if the stream has another issuer than the
     * transmitter; a stream created on the way is deleted again
     */
    public SsfStreamConfiguration register() {
        Registration registration = lookUp();
        publish(registration);
        return registration.stream();
    }

    /**
     * The outcome of a look-up: the stream, and whether it was created on the way.
     */
    private record Registration(SsfStreamConfiguration stream, String action, boolean created) {
    }

    private Registration lookUp() {
        if (this.desiredStream == null) {
            return new Registration(this.streamClient.getStream(this.streamId), "Using", false);
        }
        List<SsfStreamConfiguration> streams = this.streamClient.getStreams();
        SsfStreamConfiguration existing = streams.stream().filter(this::hasDesiredDelivery).findFirst().orElse(null);
        if (existing != null && hasDesiredEvents(existing)) {
            return new Registration(existing, "Using existing", false);
        }
        if (existing != null) {
            return new Registration(this.streamClient.updateStream(existing.streamId(), changes()), "Updated", false);
        }
        try {
            return new Registration(this.streamClient.createStream(this.desiredStream), "Created", true);
        }
        catch (SsfStreamIssuerMismatchException ex) {
            // the stream was created but must not be used, and trying again would only
            // create another one
            deleteQuietly(ex.getStream().streamId());
            throw ex;
        }
        catch (SsfStreamException ex) {
            // the transmitter allows a single stream per receiver and it is configured
            // differently
            if (ex.getStatusCode() != 409 || streams.size() != 1) {
                throw ex;
            }
            return new Registration(this.streamClient.updateStream(streams.get(0).streamId(), changes()), "Updated",
                    false);
        }
    }

    private void publish(Registration registration) {
        SsfStreamConfiguration stream = registration.stream();
        this.receiverStream.setConfiguration(stream);
        logger.info(registration.action() + " SSF stream " + stream.streamId() + " (delivery " + stream.deliveryMethod()
                + " " + Objects.toString(stream.deliveryEndpointUrl(), "") + ", events "
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

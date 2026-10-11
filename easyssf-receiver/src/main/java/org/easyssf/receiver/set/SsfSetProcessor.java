package org.easyssf.receiver.set;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.event.SsfEventHandlingException;
import org.easyssf.receiver.event.SsfSetInProgressException;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.metrics.SsfReceiverMetrics.SetOutcome;
import org.easyssf.receiver.stream.SsfStreamVerification;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes a received SET independent of how it was delivered: verifies it, skips it if
 * it was processed before and hands it to the {@link SsfEventHandler handlers}.
 *
 * <p>
 * With a {@link SsfJtiDedupStore} the SET is claimed while the handlers run. A
 * {@link #setClaimRenewal claim renewal} keeps the claim alive while a handler runs
 * longer than the store's lease; without it the lease bounds the handlers. The renewals
 * run on a daemon thread of their own, {@link #close} stops it.
 */
public class SsfSetProcessor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SsfSetProcessor.class);

    /**
     * What happened to a valid SET.
     */
    public enum Outcome {

        /**
         * The SET was handled by all handlers.
         */
        HANDLED,

        /**
         * The SET was processed before and has been skipped.
         */
        DUPLICATE

    }

    private final SsfSetVerifier verifier;

    private final SsfJtiDedupStore dedupStore;

    private final List<SsfEventHandler> handlers;

    private SsfReceiverMetrics metrics = SsfReceiverMetrics.NOOP;

    private Function<String, SsfStreamVerification> streamVerifications = (issuer) -> null;

    private Set<String> understoodSubjectMembers = SsfSubject.MEMBERS;

    private Function<String, Collection<String>> criticalSubjectMembers = (issuer) -> List.of();

    private Duration claimRenewal;

    /** created with the first renewal, a daemon thread shared by all SETs in progress */
    private volatile ScheduledExecutorService renewals;

    /** a lock rather than synchronized, which pins virtual threads before JDK 24 */
    private final ReentrantLock renewalsLock = new ReentrantLock();

    /**
     * @param verifier verifies the SETs
     * @param dedupStore remembers processed SETs, {@code null} to process duplicates
     * @param handlers invoked in order for every SET
     */
    public SsfSetProcessor(SsfSetVerifier verifier, SsfJtiDedupStore dedupStore, List<SsfEventHandler> handlers) {
        SsfAssert.notNull(verifier, "verifier must not be null");
        SsfAssert.notNull(handlers, "handlers must not be null");
        this.verifier = verifier;
        this.dedupStore = dedupStore;
        this.handlers = List.copyOf(handlers);
    }

    public void setMetrics(SsfReceiverMetrics metrics) {
        SsfAssert.notNull(metrics, "metrics must not be null");
        this.metrics = metrics;
    }

    /**
     * Renews the claim of a SET at the given interval while its handlers run
     * ({@link SsfJtiDedupStore#renew}), so that a handler that runs longer than the lease
     * of the dedup store does not lose the SET to another instance; a third of the lease
     * is a good interval. Off by default: the lease then bounds the handlers. A renewal
     * that finds the claim taken over stops, and the result of the handlers is not
     * recorded, as without renewal after the lease.
     * @param interval the interval between two renewals, {@code null} to turn renewal off
     */
    public void setClaimRenewal(Duration interval) {
        SsfAssert.isTrue(interval == null || interval.isPositive(), "interval must be positive");
        this.claimRenewal = interval;
    }

    /**
     * Sets what validates stream verification events, {@code null} to accept them
     * unchecked.
     */
    public void setStreamVerification(SsfStreamVerification streamVerification) {
        this.streamVerifications = (issuer) -> streamVerification;
    }

    /**
     * The members of a complex subject the application interprets. A SET whose subject
     * has a member the transmitter declared critical ({@code critical_subject_members} of
     * its metadata) that is not among them is rejected as {@code invalid_request}, as the
     * SSF specification requires critical members to be interpreted by the receiver. By
     * default the members {@link SsfSubject} gives access to, {@link SsfSubject#MEMBERS}.
     * @param understoodSubjectMembers the member names
     */
    public void setUnderstoodSubjectMembers(Set<String> understoodSubjectMembers) {
        SsfAssert.notNull(understoodSubjectMembers, "understoodSubjectMembers must not be null");
        this.understoodSubjectMembers = Set.copyOf(understoodSubjectMembers);
    }

    /**
     * @param criticalSubjectMembers the {@code critical_subject_members} a transmitter
     * declares in its metadata, by its issuer; empty for an unknown issuer
     * @see #setUnderstoodSubjectMembers(Set)
     */
    public void setCriticalSubjectMembers(Function<String, Collection<String>> criticalSubjectMembers) {
        SsfAssert.notNull(criticalSubjectMembers, "criticalSubjectMembers must not be null");
        this.criticalSubjectMembers = criticalSubjectMembers;
    }

    /**
     * For a receiver with several transmitters.
     * @param streamVerifications the stream verification of a transmitter by its issuer,
     * {@code null} for none
     */
    public void setStreamVerifications(Function<String, SsfStreamVerification> streamVerifications) {
        SsfAssert.notNull(streamVerifications, "streamVerifications must not be null");
        this.streamVerifications = streamVerifications;
    }

    /**
     * Verifies and handles a SET that was pushed to the receiver.
     * @see #process(String, SsfDeliveryMethod)
     */
    public Outcome process(String encodedSet) {
        return process(encodedSet, SsfDeliveryMethod.PUSH);
    }

    /**
     * Verifies and handles the given SET.
     * @param encodedSet the SET in JWS compact serialization
     * @param deliveryMethod how the SET was delivered
     * @throws SsfSetVerificationException if the SET is invalid
     * @throws SsfTransmitterUnavailableException if the transmitter's metadata or keys
     * cannot be obtained
     * @throws SsfEventHandlingException if a handler failed, the SET may be delivered
     * again
     */
    public Outcome process(String encodedSet, SsfDeliveryMethod deliveryMethod) {
        SsfEventToken eventToken;
        SsfEventContext eventContext;
        String issuer = SsfSetClaims.unverifiedIssuer(encodedSet);
        try {
            eventToken = this.verifier.verify(encodedSet);
            eventContext = new SsfEventContext(eventToken);
            SsfStreamVerification streamVerification = this.streamVerifications.apply(eventToken.iss());
            if (streamVerification != null) {
                streamVerification.validate(eventContext);
            }
            checkCriticalSubjectMembers(eventContext);
        }
        catch (SsfSetVerificationException ex) {
            this.metrics.setReceived(issuer, deliveryMethod, SetOutcome.INVALID);
            throw ex;
        }
        catch (SsfTransmitterUnavailableException ex) {
            this.metrics.setReceived(issuer, deliveryMethod, SetOutcome.UNAVAILABLE);
            throw ex;
        }
        issuer = eventToken.iss();
        SsfJtiDedupStore.Claim claim = null;
        if (this.dedupStore != null) {
            claim = this.dedupStore.claim(eventToken);
            switch (claim.state()) {
                case PROCESSED -> {
                    logger.debug("Skipping SET " + eventToken.jti() + ", it was processed before");
                    this.metrics.setReceived(issuer, deliveryMethod, SetOutcome.DUPLICATE);
                    return Outcome.DUPLICATE;
                }
                case IN_PROGRESS -> {
                    logger.debug(
                            "Leaving SET " + eventToken.jti() + " for a redelivery, another instance is handling it");
                    this.metrics.setReceived(issuer, deliveryMethod, SetOutcome.IN_PROGRESS);
                    throw new SsfSetInProgressException(
                            "SET " + eventToken.jti() + " is being handled by another instance");
                }
                case NEW -> {
                }
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Received SET " + eventToken.jti() + " (" + deliveryMethod + ") with events "
                    + eventContext.eventTypes().stream().map(SsfEventTypes::aliasOf).toList());
        }
        if (eventContext.hasEvent(SsfEventTypes.SSF_STREAM_VERIFICATION)) {
            logger.info("Received stream verification event from " + eventToken.iss());
        }
        RuntimeException failure = null;
        ClaimRenewal renewal = (claim != null && this.claimRenewal != null) ? new ClaimRenewal(eventToken, claim)
                : null;
        try {
            for (SsfEventHandler handler : this.handlers) {
                try {
                    handler.handle(eventContext);
                }
                catch (RuntimeException ex) {
                    // a failing handler must not keep the others from reacting to the
                    // event
                    logger.error(
                            "SsfEventHandler " + handler.getClass().getName() + " failed for SET " + eventToken.jti(),
                            ex);
                    if (failure == null) {
                        failure = ex;
                    }
                }
            }
        }
        finally {
            if (renewal != null) {
                // the claim as last renewed; a taken-over one no longer matches, so the
                // store ignores the outcome of the handlers
                claim = renewal.stop();
            }
        }
        if (failure != null) {
            if (claim != null) {
                this.dedupStore.forget(eventToken, claim);
            }
            this.metrics.setReceived(issuer, deliveryMethod, SetOutcome.FAILED);
            throw new SsfEventHandlingException("Could not handle SET " + eventToken.jti(), failure);
        }
        if (claim != null) {
            this.dedupStore.processed(eventToken, claim);
        }
        this.metrics.setReceived(issuer, deliveryMethod, SetOutcome.HANDLED);
        String transmitter = issuer;
        eventContext.eventTypes()
            .forEach((eventType) -> this.metrics.eventHandled(transmitter, eventType, deliveryMethod));
        return Outcome.HANDLED;
    }

    /**
     * Stops the thread that renews claims, if any was started.
     */
    @Override
    public void close() {
        ScheduledExecutorService renewals = this.renewals;
        if (renewals != null) {
            renewals.shutdownNow();
        }
    }

    private ScheduledExecutorService renewals() {
        ScheduledExecutorService renewals = this.renewals;
        if (renewals == null) {
            this.renewalsLock.lock();
            try {
                renewals = this.renewals;
                if (renewals == null) {
                    renewals = Executors.newSingleThreadScheduledExecutor((task) -> {
                        Thread thread = new Thread(task, "easyssf-claim-renewal");
                        thread.setDaemon(true);
                        return thread;
                    });
                    this.renewals = renewals;
                }
            }
            finally {
                this.renewalsLock.unlock();
            }
        }
        return renewals;
    }

    /**
     * The claim of a SET in progress, renewed at the {@link #setClaimRenewal interval}
     * until the handlers are done. A renewal and the end of the handlers exclude each
     * other, so the claim the outcome is recorded with is the one the store holds.
     */
    private final class ClaimRenewal implements Runnable {

        private final SsfEventToken eventToken;

        private final ReentrantLock lock = new ReentrantLock();

        private final ScheduledFuture<?> task;

        private SsfJtiDedupStore.Claim claim;

        private boolean stopped;

        ClaimRenewal(SsfEventToken eventToken, SsfJtiDedupStore.Claim claim) {
            this.eventToken = eventToken;
            this.claim = claim;
            long interval = SsfSetProcessor.this.claimRenewal.toMillis();
            this.task = renewals().scheduleAtFixedRate(this, interval, interval, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run() {
            this.lock.lock();
            try {
                if (this.stopped) {
                    return;
                }
                SsfJtiDedupStore.Claim renewed = SsfSetProcessor.this.dedupStore.renew(this.eventToken, this.claim);
                if (renewed == null) {
                    logger.warn("SET " + this.eventToken.jti() + " was taken over by another instance while its "
                            + "handlers were still running; the lease of the dedup store is shorter than they take");
                    this.stopped = true;
                }
                else {
                    this.claim = renewed;
                }
            }
            catch (RuntimeException ex) {
                // tried again at the next interval
                logger.warn("Could not renew the claim of SET " + this.eventToken.jti() + ": " + ex.getMessage());
            }
            finally {
                this.lock.unlock();
            }
        }

        /**
         * @return the claim as last renewed, once no renewal runs any more
         */
        SsfJtiDedupStore.Claim stop() {
            this.task.cancel(false);
            this.lock.lock();
            try {
                this.stopped = true;
                return this.claim;
            }
            finally {
                this.lock.unlock();
            }
        }

    }

    private void checkCriticalSubjectMembers(SsfEventContext eventContext) {
        Collection<String> critical = this.criticalSubjectMembers.apply(eventContext.eventToken().iss());
        if (critical == null || critical.isEmpty()) {
            return;
        }
        Set<String> present = eventContext.subject().memberNames();
        List<String> notUnderstood = critical.stream()
            .filter((member) -> present.contains(member) && !this.understoodSubjectMembers.contains(member))
            .toList();
        if (!notUnderstood.isEmpty()) {
            throw new SsfSetVerificationException(SsfSetVerificationException.INVALID_REQUEST,
                    "The subject has the members " + notUnderstood + " which the transmitter declared critical"
                            + " and this receiver does not understand");
        }
    }

}

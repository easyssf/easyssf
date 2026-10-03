package org.easyssf.receiver.spring.boot.health;

import java.time.Instant;

import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports whether the receiver is in contact with its transmitter, from what the receiver
 * already knows. The transmitter is never called for the health check.
 * <ul>
 * <li>{@link Status#UP}: the transmitter was reached, that is its metadata was retrieved
 * (PUSH) or the last poll succeeded (POLL), and the stream, if it is looked up or
 * managed, is registered.</li>
 * <li>{@link Status#DOWN}: the last poll failed, or the stream cannot be used.</li>
 * <li>{@link Status#UNKNOWN}: no contact with the transmitter yet. The application starts
 * without it.</li>
 * </ul>
 */
public class SsfReceiverHealthIndicator implements HealthIndicator {

    private final String transmitterIssuer;

    private final SsfTransmitterMetadataResolver metadataResolver;

    private final SsfReceiverStream receiverStream;

    private final SsfStreamRegistrar streamRegistrar;

    private final SsfPoller poller;

    /**
     * @param transmitterIssuer the issuer of the transmitter
     * @param metadataResolver resolves the metadata of the transmitter
     * @param receiverStream the stream of the receiver
     * @param streamRegistrar registers the stream, {@code null} if the stream is not
     * looked up or managed
     * @param poller polls the transmitter, {@code null} with PUSH delivery
     */
    public SsfReceiverHealthIndicator(String transmitterIssuer, SsfTransmitterMetadataResolver metadataResolver,
            SsfReceiverStream receiverStream, SsfStreamRegistrar streamRegistrar, SsfPoller poller) {
        this.transmitterIssuer = transmitterIssuer;
        this.metadataResolver = metadataResolver;
        this.receiverStream = receiverStream;
        this.streamRegistrar = streamRegistrar;
        this.poller = poller;
    }

    @Override
    public Health health() {
        Health.Builder health = new Health.Builder();
        Status status = Status.UNKNOWN;
        detail(health, "transmitter", this.transmitterIssuer);
        detail(health, "delivery", (this.poller != null) ? "poll" : "push");

        SsfTransmitterMetadata metadata = this.metadataResolver.getResolvedMetadata().orElse(null);
        detail(health, "metadata", (metadata != null) ? "resolved" : "not retrieved yet");
        if (metadata != null) {
            detail(health, "jwksUri", metadata.jwksUri());
            status = Status.UP;
        }

        String streamId = this.receiverStream.getStreamId();
        detail(health, "stream", (streamId != null) ? streamId : "none");
        if (this.streamRegistrar != null) {
            SsfStreamRegistrar.State state = this.streamRegistrar.getState();
            detail(health, "streamRegistration", state.name().toLowerCase());
            detail(health, "streamRegistrationError", this.streamRegistrar.getLastError());
            if (state == SsfStreamRegistrar.State.FAILED) {
                status = Status.DOWN;
            }
            else if (state != SsfStreamRegistrar.State.REGISTERED) {
                status = Status.UNKNOWN;
            }
        }

        if (this.poller != null) {
            Instant lastPoll = this.poller.getLastPollAt();
            Instant lastSuccess = this.poller.getLastSuccessfulPollAt();
            String error = this.poller.getLastPollError();
            detail(health, "lastPoll", lastPoll);
            detail(health, "lastSuccessfulPoll", lastSuccess);
            detail(health, "pollError", error);
            Instant pausedUntil = this.poller.getPausedUntil();
            if (pausedUntil != null && pausedUntil.isAfter(Instant.now())) {
                detail(health, "pausedUntil", pausedUntil);
            }
            if (error != null) {
                status = Status.DOWN;
            }
            else if (lastSuccess == null) {
                if (status != Status.DOWN) {
                    status = Status.UNKNOWN;
                }
            }
            else if (status == Status.UP) {
                status = Status.UP;
            }
        }
        return health.status(status).build();
    }

    private static void detail(Health.Builder health, String key, Object value) {
        if (value != null) {
            health.withDetail(key, value.toString());
        }
    }

}

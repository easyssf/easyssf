package org.easyssf.receiver.spring.boot.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports whether the receiver is in contact with its transmitters, from what the
 * receiver already knows. A transmitter is never called for the health check.
 * <ul>
 * <li>{@link Status#UP}: every transmitter was reached, that is its metadata was
 * retrieved (PUSH) or the last poll succeeded (POLL), and its stream, if it is looked up
 * or managed, is registered.</li>
 * <li>{@link Status#DOWN}: a last poll failed, or a stream cannot be used.</li>
 * <li>{@link Status#UNKNOWN}: no contact with a transmitter yet. The application starts
 * without it.</li>
 * </ul>
 * With one transmitter its details are the details of the health; with several, the
 * details of each are listed under its name.
 */
public class SsfReceiverHealthIndicator implements HealthIndicator {

    private final SsfTransmitters transmitters;

    public SsfReceiverHealthIndicator(SsfTransmitters transmitters) {
        this.transmitters = transmitters;
    }

    @Override
    public Health health() {
        List<SsfTransmitter> all = this.transmitters.all();
        Health.Builder health = new Health.Builder();
        Status status = Status.UP;
        if (all.size() == 1) {
            SsfTransmitter transmitter = all.get(0);
            status = status(transmitter, (key, value) -> detail(health, key, value));
        }
        else {
            for (SsfTransmitter transmitter : all) {
                Map<String, Object> details = new LinkedHashMap<>();
                Status own = status(transmitter, (key, value) -> {
                    if (value != null) {
                        details.put(key, value.toString());
                    }
                });
                details.put("status", own.getCode());
                health.withDetail(transmitter.getName(), details);
                status = worse(status, own);
            }
        }
        return health.status(status).build();
    }

    private static Status status(SsfTransmitter transmitter, Details details) {
        Status status = Status.UNKNOWN;
        details.put("transmitter", transmitter.getIssuer());
        details.put("delivery", (transmitter.getPoller() != null) ? "poll" : "push");

        SsfTransmitterMetadata metadata = transmitter.getMetadataResolver().getResolvedMetadata().orElse(null);
        details.put("metadata", (metadata != null) ? "resolved" : "not retrieved yet");
        if (metadata != null) {
            details.put("jwksUri", metadata.jwksUri());
            status = Status.UP;
        }

        String streamId = transmitter.getReceiverStream().getStreamId();
        details.put("stream", (streamId != null) ? streamId : "none");
        SsfStreamRegistrar registrar = transmitter.getStreamRegistrar();
        if (registrar != null) {
            SsfStreamRegistrar.State state = registrar.getState();
            details.put("streamRegistration", state.name().toLowerCase());
            details.put("streamRegistrationError", registrar.getLastError());
            if (state == SsfStreamRegistrar.State.FAILED) {
                status = Status.DOWN;
            }
            else if (state != SsfStreamRegistrar.State.REGISTERED) {
                status = Status.UNKNOWN;
            }
        }

        SsfPoller poller = transmitter.getPoller();
        if (poller != null) {
            Instant lastSuccess = poller.getLastSuccessfulPollAt();
            String error = poller.getLastPollError();
            details.put("lastPoll", poller.getLastPollAt());
            details.put("lastSuccessfulPoll", lastSuccess);
            details.put("pollError", error);
            Instant pausedUntil = poller.getPausedUntil();
            if (pausedUntil != null && pausedUntil.isAfter(Instant.now())) {
                details.put("pausedUntil", pausedUntil);
            }
            if (error != null) {
                status = Status.DOWN;
            }
            else if (lastSuccess == null && status != Status.DOWN) {
                status = Status.UNKNOWN;
            }
        }
        return status;
    }

    private static Status worse(Status current, Status other) {
        if (current == Status.DOWN || other == Status.DOWN) {
            return Status.DOWN;
        }
        if (current == Status.UNKNOWN || other == Status.UNKNOWN) {
            return Status.UNKNOWN;
        }
        return Status.UP;
    }

    private static void detail(Health.Builder health, String key, Object value) {
        if (value != null) {
            health.withDetail(key, value.toString());
        }
    }

    @FunctionalInterface
    private interface Details {

        void put(String key, Object value);

    }

}

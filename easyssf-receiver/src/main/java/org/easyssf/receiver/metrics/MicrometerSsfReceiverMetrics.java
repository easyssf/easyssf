package org.easyssf.receiver.metrics;

import java.time.Duration;
import java.util.Locale;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.support.SsfAssert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * {@link SsfReceiverMetrics} that records to a Micrometer {@link MeterRegistry}:
 *
 * <ul>
 * <li>{@code easyssf.receiver.sets}: received SETs, tagged with {@code delivery}
 * ({@code push}, {@code poll}) and {@code outcome} ({@code handled}, {@code duplicate},
 * {@code invalid}, {@code unauthenticated}, {@code unavailable}, {@code failed})</li>
 * <li>{@code easyssf.receiver.events}: handled events, tagged with {@code delivery} and
 * {@code event} (for example {@code CaepSessionRevoked})</li>
 * <li>{@code easyssf.receiver.poll}: poll requests, tagged with {@code outcome}
 * ({@code success}, {@code failure})</li>
 * </ul>
 */
public class MicrometerSsfReceiverMetrics implements SsfReceiverMetrics {

    private final MeterRegistry meterRegistry;

    public MicrometerSsfReceiverMetrics(MeterRegistry meterRegistry) {
        SsfAssert.notNull(meterRegistry, "meterRegistry must not be null");
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void setReceived(SsfDeliveryMethod deliveryMethod, SetOutcome outcome) {
        Counter.builder("easyssf.receiver.sets")
            .description("Security event tokens received")
            .tag("delivery", tag(deliveryMethod))
            .tag("outcome", tag(outcome))
            .register(this.meterRegistry)
            .increment();
    }

    @Override
    public void eventHandled(String eventType, SsfDeliveryMethod deliveryMethod) {
        Counter.builder("easyssf.receiver.events")
            .description("Security events handled")
            .tag("delivery", tag(deliveryMethod))
            .tag("event", SsfEventTypes.aliasOf(eventType))
            .register(this.meterRegistry)
            .increment();
    }

    @Override
    public void pollCompleted(Duration duration, boolean success) {
        Timer.builder("easyssf.receiver.poll")
            .description("Poll requests to the transmitter")
            .tag("outcome", success ? "success" : "failure")
            .register(this.meterRegistry)
            .record(duration);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

}

package org.easyssf.conformance.receiver;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.receiver.http.SsfHttpClient;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Starts and keeps the current {@link ConformanceRun}. There is at most one run at a
 * time, since the suite's tests run one after another and the push endpoint belongs to
 * the current run.
 */
@Component
public class ConformanceRunner {

    private final CtsProperties properties;

    private final SsfHttpClient httpClient;

    private final AtomicInteger runIds = new AtomicInteger();

    private final AtomicReference<ConformanceRun> current = new AtomicReference<>();

    ConformanceRunner(CtsProperties properties, SsfHttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /**
     * Starts a run against the configured transmitter with the configured delivery
     * method, stopping the current run if it is still active.
     */
    public ConformanceRun start(ConformanceScenario scenario) {
        return start(scenario, null, null);
    }

    /**
     * Starts a run, stopping the current one if it is still active.
     * @param issuer the issuer of the emulated transmitter, {@code null} for the
     * configured one ({@code cts.transmitter.issuer})
     * @param deliveryMethod the delivery method to request, {@code null} for the
     * configured one ({@code cts.delivery.method})
     */
    public ConformanceRun start(ConformanceScenario scenario, String issuer, SsfDeliveryMethod deliveryMethod) {
        return start(scenario, issuer, deliveryMethod, null);
    }

    /**
     * Starts a run, stopping the current one if it is still active.
     * @param issuer the issuer of the emulated transmitter, {@code null} for the
     * configured one ({@code cts.transmitter.issuer})
     * @param deliveryMethod the delivery method to request, {@code null} for the
     * configured one ({@code cts.delivery.method})
     * @param idleTimeout how long the run waits for further events before it deletes the
     * stream, {@code null} for the configured one ({@code cts.run.idle-timeout})
     */
    public synchronized ConformanceRun start(ConformanceScenario scenario, String issuer,
            SsfDeliveryMethod deliveryMethod, Duration idleTimeout) {
        if (!StringUtils.hasText(issuer)) {
            issuer = this.properties.getTransmitter().getIssuer();
            Assert.hasText(issuer, "cts.transmitter.issuer must be set when no issuer is given for the run");
        }
        if (deliveryMethod == null) {
            deliveryMethod = this.properties.getDelivery().getMethod();
        }
        ConformanceRun previous = this.current.get();
        if (previous != null && previous.isActive()) {
            previous.stop();
        }
        ConformanceRun run = new ConformanceRun(String.valueOf(this.runIds.incrementAndGet()), scenario, issuer,
                deliveryMethod, this.properties, this.httpClient);
        if (idleTimeout != null) {
            run.setIdleTimeout(idleTimeout);
        }
        this.current.set(run);
        run.start();
        return run;
    }

    public Optional<ConformanceRun> current() {
        return Optional.ofNullable(this.current.get());
    }

}

package org.easyssf.conformance.receiver;

import java.time.Duration;
import java.util.Map;

/**
 * What the receiver does for each test module of the suite's receiver plans and how its
 * run is expected to end.
 */
final class ReceiverModules {

    /**
     * How long the receiver waits for further events before it deletes the stream. The
     * suite delivers the events of most modules back to back (gaps of at most about 2
     * seconds), so this is far below {@code cts.run.idle-timeout}.
     */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The idle timeout for the modules that pause between events on purpose: 20 seconds
     * token lifetime in the access token expiry test, 15 seconds in the
     * transmitter-initiated status change test.
     */
    static final Duration PAUSING_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The scenario the receiver plays, the status its run is expected to end with and how
     * long it waits for further events.
     */
    record Expectation(ConformanceScenario scenario, ConformanceRun.Status runStatus, Duration idleTimeout) {

        Expectation(ConformanceScenario scenario, ConformanceRun.Status runStatus) {
            this(scenario, runStatus, IDLE_TIMEOUT);
        }

    }

    /**
     * How a module's run ends and how long it waits for events, where that differs from a
     * finished run with the usual idle timeout. The scenario comes from
     * {@link ConformanceScenario#forModule(String)}.
     */
    private record Outcome(ConformanceRun.Status runStatus, Duration idleTimeout) {
    }

    private static final Outcome DEFAULT = new Outcome(ConformanceRun.Status.FINISHED, IDLE_TIMEOUT);

    private static final Map<String, Outcome> OUTCOMES = Map.of(
            // the transmitter's issuer differs from the one the stream is created at
            "openid-ssf-receiver-stream-issuer-mismatch",
            new Outcome(ConformanceRun.Status.REFUSED_STREAM, IDLE_TIMEOUT), "openid-ssf-receiver-access-token-expiry",
            new Outcome(ConformanceRun.Status.FINISHED, PAUSING_IDLE_TIMEOUT),
            "openid-ssf-receiver-transmitter-initiated-status-change",
            new Outcome(ConformanceRun.Status.FINISHED, PAUSING_IDLE_TIMEOUT));

    private ReceiverModules() {
    }

    static Expectation expectation(String moduleName) {
        Outcome outcome = OUTCOMES.getOrDefault(moduleName, DEFAULT);
        return new Expectation(ConformanceScenario.forModule(moduleName), outcome.runStatus(), outcome.idleTimeout());
    }

}

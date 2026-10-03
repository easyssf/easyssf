package org.easyssf.test.conformance.receiver;

import java.util.Map;

/**
 * What the receiver does with its stream in a test run. All scenarios create the stream,
 * check its issuer, and delete it at the end; the ones that keep the stream read its
 * configuration and status, verify it and accept the events the transmitter delivers
 * until none arrives for a while.
 */
public enum ConformanceScenario {

    /**
     * Fits the CAEP interop plan and most tests of the default plan.
     */
    CAEP_INTEROP,

    /**
     * Creates and deletes the stream, nothing else.
     */
    CREATE_DELETE,

    /**
     * Additionally updates and replaces the stream configuration (receiver happy path
     * test of the default plan).
     */
    STREAM_MANAGEMENT,

    /**
     * Additionally pauses and enables the stream (stream status test of the default
     * plan).
     */
    STATUS_UPDATE,

    /**
     * Additionally removes a subject from the stream (removed subject test of the default
     * plan).
     */
    REMOVE_SUBJECT;

    /**
     * The test modules of the suite's receiver plans that need another scenario than
     * {@link #CAEP_INTEROP}, which fits all tests that just expect a stream to be
     * created, verified, fed and deleted.
     */
    private static final Map<String, ConformanceScenario> MODULE_SCENARIOS = Map.of("openid-ssf-receiver-happypath",
            STREAM_MANAGEMENT, "openid-ssf-receiver-stream-create-delete", CREATE_DELETE,
            "openid-ssf-receiver-stream-status-update", STATUS_UPDATE, "openid-ssf-receiver-removed-subject-event",
            REMOVE_SUBJECT);

    /**
     * The name used in the API, e.g. {@code caep-interop}.
     */
    public String alias() {
        return name().toLowerCase().replace('_', '-');
    }

    public static ConformanceScenario fromAlias(String alias) {
        for (ConformanceScenario scenario : values()) {
            if (scenario.alias().equalsIgnoreCase(alias) || scenario.name().equalsIgnoreCase(alias)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown scenario '" + alias + "'");
    }

    /**
     * The scenario the receiver plays for a test module of the suite, e.g.
     * {@code openid-ssf-receiver-stream-create-delete}.
     */
    public static ConformanceScenario forModule(String moduleName) {
        return MODULE_SCENARIOS.getOrDefault(moduleName, CAEP_INTEROP);
    }

}

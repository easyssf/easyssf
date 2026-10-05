package org.easyssf.core.caep;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.easyssf.core.event.SsfEventTypes;

/**
 * The events of CAEP 1.0, section 3, and {@link #OTHER} for an event type in the CAEP
 * namespace this version does not know. New constants are added as CAEP defines events,
 * so do not switch over this enum exhaustively; subclass {@code SsfCaepEventHandler}
 * instead.
 */
public enum SsfCaepEventKind {

    /**
     * The session was revoked (section 3.1).
     */
    SESSION_REVOKED(SsfEventTypes.CAEP_SESSION_REVOKED),

    /**
     * Claims of the token the subject identifies changed (section 3.2).
     */
    TOKEN_CLAIMS_CHANGE(SsfEventTypes.CAEP_TOKEN_CLAIMS_CHANGE),

    /**
     * A credential was created, revoked, updated or deleted (section 3.3).
     */
    CREDENTIAL_CHANGE(SsfEventTypes.CAEP_CREDENTIAL_CHANGE),

    /**
     * The assurance level of the subject changed (section 3.4).
     */
    ASSURANCE_LEVEL_CHANGE(SsfEventTypes.CAEP_ASSURANCE_LEVEL_CHANGE),

    /**
     * The compliance status of the device changed (section 3.5).
     */
    DEVICE_COMPLIANCE_CHANGE(SsfEventTypes.CAEP_DEVICE_COMPLIANCE_CHANGE),

    /**
     * A session was established (section 3.6).
     */
    SESSION_ESTABLISHED(SsfEventTypes.CAEP_SESSION_ESTABLISHED),

    /**
     * A session was presented to the transmitter (section 3.7).
     */
    SESSION_PRESENTED(SsfEventTypes.CAEP_SESSION_PRESENTED),

    /**
     * The risk level of the subject changed (section 3.8).
     */
    RISK_LEVEL_CHANGE(SsfEventTypes.CAEP_RISK_LEVEL_CHANGE),

    /**
     * An event type in the CAEP namespace this version does not know, for example one of
     * a later CAEP version. The common claims of CAEP section 2 are still available.
     */
    OTHER(null);

    private static final Map<String, SsfCaepEventKind> BY_EVENT_TYPE = Arrays.stream(values())
        .filter((kind) -> kind.eventType != null)
        .collect(Collectors.toUnmodifiableMap((kind) -> kind.eventType, Function.identity()));

    private final String eventType;

    SsfCaepEventKind(String eventType) {
        this.eventType = eventType;
    }

    /**
     * @return the event type URI, {@code null} for {@link #OTHER}
     */
    public String eventType() {
        return this.eventType;
    }

    /**
     * @param eventType an event type URI or alias
     * @return the kind of CAEP event, {@link #OTHER} for an unknown event type in the
     * CAEP namespace, {@code null} if the event type is not a CAEP event
     */
    public static SsfCaepEventKind of(String eventType) {
        if (!SsfEventTypes.isCaepEvent(eventType)) {
            return null;
        }
        return BY_EVENT_TYPE.getOrDefault(SsfEventTypes.resolve(eventType), OTHER);
    }

}

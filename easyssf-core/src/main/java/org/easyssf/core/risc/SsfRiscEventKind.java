package org.easyssf.core.risc;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.easyssf.core.event.SsfEventTypes;

/**
 * The events of RISC 1.0, section 2, and {@link #OTHER} for an event type in the RISC
 * namespace this version does not know. New constants are added as RISC defines events,
 * so do not switch over this enum exhaustively; subclass {@code SsfRiscEventHandler}
 * instead.
 */
public enum SsfRiscEventKind {

    /**
     * The account has to change its credential, for example after a leak elsewhere
     * (section 2.1).
     */
    ACCOUNT_CREDENTIAL_CHANGE_REQUIRED(SsfEventTypes.RISC_ACCOUNT_CREDENTIAL_CHANGE_REQUIRED),

    /**
     * The account was permanently deleted (section 2.2).
     */
    ACCOUNT_PURGED(SsfEventTypes.RISC_ACCOUNT_PURGED),

    /**
     * The account was disabled, see {@link SsfRiscEvent#reason()} (section 2.3).
     */
    ACCOUNT_DISABLED(SsfEventTypes.RISC_ACCOUNT_DISABLED),

    /**
     * The account was enabled again (section 2.4).
     */
    ACCOUNT_ENABLED(SsfEventTypes.RISC_ACCOUNT_ENABLED),

    /**
     * The identifier of the subject, an email address or phone number, changed to
     * {@link SsfRiscEvent#newValue()} (section 2.5).
     */
    IDENTIFIER_CHANGED(SsfEventTypes.RISC_IDENTIFIER_CHANGED),

    /**
     * The identifier was given to someone else (section 2.6).
     */
    IDENTIFIER_RECYCLED(SsfEventTypes.RISC_IDENTIFIER_RECYCLED),

    /**
     * A credential of the subject was found compromised, see
     * {@link SsfRiscEvent#credentialType()} (section 2.7).
     */
    CREDENTIAL_COMPROMISE(SsfEventTypes.RISC_CREDENTIAL_COMPROMISE),

    /**
     * The account takes part in the RISC exchange (section 2.8.1).
     */
    OPT_IN(SsfEventTypes.RISC_OPT_IN),

    /**
     * The user asked to leave the RISC exchange (section 2.8.2).
     */
    OPT_OUT_INITIATED(SsfEventTypes.RISC_OPT_OUT_INITIATED),

    /**
     * The user withdrew the request to leave (section 2.8.3).
     */
    OPT_OUT_CANCELLED(SsfEventTypes.RISC_OPT_OUT_CANCELLED),

    /**
     * The account left the RISC exchange (section 2.8.4).
     */
    OPT_OUT_EFFECTIVE(SsfEventTypes.RISC_OPT_OUT_EFFECTIVE),

    /**
     * The account went through a recovery flow (section 2.9).
     */
    RECOVERY_ACTIVATED(SsfEventTypes.RISC_RECOVERY_ACTIVATED),

    /**
     * The recovery information of the account changed (section 2.10).
     */
    RECOVERY_INFORMATION_CHANGED(SsfEventTypes.RISC_RECOVERY_INFORMATION_CHANGED),

    /**
     * All sessions of the account were revoked (section 2.11). Deprecated by RISC in
     * favour of CAEP's {@code session-revoked}, still sent by some transmitters.
     */
    SESSIONS_REVOKED(SsfEventTypes.RISC_SESSIONS_REVOKED),

    /**
     * An event type in the RISC namespace this version does not know.
     */
    OTHER(null);

    private static final Map<String, SsfRiscEventKind> BY_EVENT_TYPE = Arrays.stream(values())
        .filter((kind) -> kind.eventType != null)
        .collect(Collectors.toUnmodifiableMap((kind) -> kind.eventType, Function.identity()));

    private final String eventType;

    SsfRiscEventKind(String eventType) {
        this.eventType = eventType;
    }

    /**
     * @return the event type URI, {@code null} for {@link #OTHER}
     */
    public String eventType() {
        return this.eventType;
    }

    /**
     * Whether this is one of the opt-in and opt-out events of section 2.8, which report
     * the participation of the account in the exchange rather than a risk.
     */
    public boolean isOptOutState() {
        return this == OPT_IN || this == OPT_OUT_INITIATED || this == OPT_OUT_CANCELLED || this == OPT_OUT_EFFECTIVE;
    }

    /**
     * @param eventType an event type URI or alias
     * @return the kind of RISC event, {@link #OTHER} for an unknown event type in the
     * RISC namespace, {@code null} if the event type is not a RISC event
     */
    public static SsfRiscEventKind of(String eventType) {
        if (!SsfEventTypes.isRiscEvent(eventType)) {
            return null;
        }
        return BY_EVENT_TYPE.getOrDefault(SsfEventTypes.resolve(eventType), OTHER);
    }

}

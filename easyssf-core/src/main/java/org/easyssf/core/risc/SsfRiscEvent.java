package org.easyssf.core.risc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventTimestamps;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.core.support.SsfCollections;

/**
 * A RISC 1.0 event of a SET, with typed access to the few attributes RISC defines: the
 * {@link #reason()} of an account-disabled, the {@link #newValue()} of an
 * identifier-changed, and the {@link #credentialType()}, {@link #eventTimestamp()} and
 * reasons of a credential-compromise. Most RISC events have no attributes; the
 * {@link #payload()} is the event as received for anything else. An accessor returns
 * {@code null} when the attribute is absent, whether because the transmitter left it out
 * or because the {@link #kind()} does not define it.
 *
 * <p>
 * The subject of the event is the {@code sub_id} of the SET,
 * {@code SsfEventContext.subject()} in a handler.
 *
 * @param eventType the event type URI
 * @param kind the kind of event, {@link SsfRiscEventKind#OTHER} for a RISC event type
 * this version does not know
 * @param payload the event payload as received
 */
public record SsfRiscEvent(String eventType, SsfRiscEventKind kind, Map<String, Object> payload) {

    public SsfRiscEvent {
        SsfAssert.notNull(eventType, "eventType must not be null");
        SsfAssert.notNull(kind, "kind must not be null");
        payload = SsfCollections.copyOf((payload != null) ? payload : Map.of());
    }

    /**
     * @param eventType the event type URI or alias
     * @param payload the event payload, may be {@code null}
     * @return the event, {@code null} if the event type is not a RISC event
     */
    public static SsfRiscEvent of(String eventType, Map<String, Object> payload) {
        SsfRiscEventKind kind = SsfRiscEventKind.of(eventType);
        if (kind == null) {
            return null;
        }
        return new SsfRiscEvent(SsfEventTypes.resolve(eventType), kind, payload);
    }

    /**
     * The RISC events of a SET, in the order of its {@code events} claim.
     * @param eventToken a verified SET
     * @return the RISC events, empty if the SET carries none
     */
    @SuppressWarnings("unchecked")
    public static List<SsfRiscEvent> in(SsfEventToken eventToken) {
        SsfAssert.notNull(eventToken, "eventToken must not be null");
        List<SsfRiscEvent> events = new ArrayList<>();
        for (Map.Entry<String, Object> entry : eventToken.events().entrySet()) {
            SsfRiscEventKind kind = SsfRiscEventKind.of(entry.getKey());
            if (kind != null) {
                Map<String, Object> payload = (entry.getValue() instanceof Map<?, ?> map) ? (Map<String, Object>) map
                        : Map.of();
                events.add(new SsfRiscEvent(entry.getKey(), kind, payload));
            }
        }
        return List.copyOf(events);
    }

    /**
     * @return the {@code reason} of an {@link SsfRiscEventKind#ACCOUNT_DISABLED}:
     * {@code hijacking} or {@code bulk-account}; {@code null} if absent
     */
    public String reason() {
        return string("reason");
    }

    /**
     * @return the {@code new-value} of an {@link SsfRiscEventKind#IDENTIFIER_CHANGED},
     * the new identifier; {@code null} if absent
     */
    public String newValue() {
        return string("new-value");
    }

    /**
     * @return the {@code credential_type} of a
     * {@link SsfRiscEventKind#CREDENTIAL_COMPROMISE}, one of the values of CAEP's
     * credential-change ({@code password}, {@code pin}, {@code x509}, ...); {@code null}
     * if absent
     */
    public String credentialType() {
        return string("credential_type");
    }

    /**
     * @return the {@code event_timestamp} of a
     * {@link SsfRiscEventKind#CREDENTIAL_COMPROMISE}, when the transmitter discovered the
     * compromise, {@code null} if absent. RISC defines seconds since the epoch; a value
     * that is clearly milliseconds is accepted too.
     */
    public Instant eventTimestamp() {
        return SsfEventTimestamps.from(this.payload.get("event_timestamp"));
    }

    /**
     * @return the {@code reason_admin} of a
     * {@link SsfRiscEventKind#CREDENTIAL_COMPROMISE}, the reason for administrators,
     * {@code null} if absent. RISC leaves the type open; a localizable object as CAEP
     * defines it is returned as its JSON text.
     */
    public String reasonAdmin() {
        return string("reason_admin");
    }

    /**
     * @return the {@code reason_user} of a
     * {@link SsfRiscEventKind#CREDENTIAL_COMPROMISE}, the reason for the end user,
     * {@code null} if absent
     */
    public String reasonUser() {
        return string("reason_user");
    }

    private String string(String name) {
        Object value = this.payload.get(name);
        return (value != null) ? value.toString() : null;
    }

}

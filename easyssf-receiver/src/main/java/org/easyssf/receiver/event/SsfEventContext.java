package org.easyssf.receiver.event;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;

/**
 * A verified {@link SsfEventToken} together with convenient, alias-aware access to its
 * events and subjects. Passed to every {@link SsfEventHandler}.
 */
public final class SsfEventContext {

    private final SsfEventToken eventToken;

    public SsfEventContext(SsfEventToken eventToken) {
        this.eventToken = eventToken;
    }

    public SsfEventToken eventToken() {
        return this.eventToken;
    }

    /**
     * The event type URIs contained in the SET.
     */
    public Set<String> eventTypes() {
        return Collections.unmodifiableSet(this.eventToken.events().keySet());
    }

    /**
     * Whether the SET contains an event of the given type.
     * @param aliasOrUri an alias such as {@code CaepSessionRevoked} or an event type URI
     * @see SsfEventTypes
     */
    public boolean hasEvent(String aliasOrUri) {
        return aliasOrUri != null && this.eventToken.events().containsKey(SsfEventTypes.resolve(aliasOrUri));
    }

    /**
     * The payload of the event of the given type.
     * @param aliasOrUri an alias such as {@code CaepSessionRevoked} or an event type URI
     * @return the payload, or {@code null} if the SET has no such event or its payload is
     * not a JSON object
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> eventFor(String aliasOrUri) {
        if (aliasOrUri == null) {
            return null;
        }
        Object payload = this.eventToken.events().get(SsfEventTypes.resolve(aliasOrUri));
        return (payload instanceof Map<?, ?> map) ? (Map<String, Object>) map : null;
    }

    /**
     * The subject of the SET, taken from its top-level {@code sub_id} claim.
     * @return the subject, {@link SsfSubject#isEmpty() empty} if the SET has none
     */
    public SsfSubject subject() {
        return SsfSubject.from(this.eventToken.subjectId());
    }

    /**
     * The subject of the event of the given type: the top-level {@code sub_id} claim of
     * the SET or, for transmitters following earlier SSF drafts, the {@code subject}
     * member of the event payload.
     */
    @SuppressWarnings("unchecked")
    public SsfSubject subjectFor(String aliasOrUri) {
        SsfSubject subject = subject();
        if (!subject.isEmpty()) {
            return subject;
        }
        Map<String, Object> event = eventFor(aliasOrUri);
        if (event != null && event.get("subject") instanceof Map<?, ?> legacySubject) {
            return SsfSubject.from((Map<String, Object>) legacySubject);
        }
        return subject;
    }

    /**
     * The time the event of the given type occurred: its {@code event_timestamp} or, if
     * absent, the time the SET was issued.
     */
    public Instant eventTimestamp(String aliasOrUri) {
        Map<String, Object> event = eventFor(aliasOrUri);
        if (event != null && event.get("event_timestamp") instanceof Number timestamp) {
            long value = timestamp.longValue();
            // CAEP defines seconds since epoch, some transmitters send milliseconds
            return (value > 100_000_000_000L) ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        return this.eventToken.iat();
    }

}

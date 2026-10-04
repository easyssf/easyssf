package org.easyssf.core.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.core.support.SsfCollections;

/**
 * A SCIM Event (RFC 9967) of a SET, with typed access to the common payload attributes of
 * section 2.2: the resource as {@code data} in {@code full} mode, the modified
 * {@code attributes} in {@code notice} mode, and the ETag {@code version} of the resource
 * after the event. The {@code txn} that groups the SETs of one transaction is a claim of
 * the {@link SsfEventToken}.
 *
 * <p>
 * A SET may carry several SCIM Events; RFC 9967 then has them arise from the same change
 * of the same resource, so they share the {@link #subject()}.
 *
 * @param eventType the event type URI
 * @param operation what the event reports
 * @param subject the SCIM resource the event is about, {@code null} if the {@code sub_id}
 * of the SET is not a {@code scim} subject identifier (which RFC 9967 requires)
 * @param payload the event payload as received
 */
public record SsfScimEvent(String eventType, SsfScimOperation operation, SsfScimSubject subject,
        Map<String, Object> payload) {

    public SsfScimEvent {
        SsfAssert.notNull(eventType, "eventType must not be null");
        SsfAssert.notNull(operation, "operation must not be null");
        payload = SsfCollections.copyOf((payload != null) ? payload : Map.of());
    }

    /**
     * @param eventType the event type URI or alias
     * @param subject the SCIM subject of the SET, may be {@code null}
     * @param payload the event payload, may be {@code null}
     * @return the event, {@code null} if the event type is not one of SCIM Events
     */
    public static SsfScimEvent of(String eventType, SsfScimSubject subject, Map<String, Object> payload) {
        SsfScimOperation operation = SsfScimOperation.of(eventType);
        if (operation == null) {
            return null;
        }
        return new SsfScimEvent(SsfEventTypes.resolve(eventType), operation, subject, payload);
    }

    /**
     * The SCIM Events of a SET, in the order of its {@code events} claim.
     * @param eventToken a verified SET
     * @return the SCIM Events, empty if the SET carries none
     */
    @SuppressWarnings("unchecked")
    public static List<SsfScimEvent> in(SsfEventToken eventToken) {
        SsfAssert.notNull(eventToken, "eventToken must not be null");
        SsfScimSubject subject = SsfScimSubject.from(SsfSubject.from(eventToken.subjectId()));
        List<SsfScimEvent> events = new ArrayList<>();
        for (Map.Entry<String, Object> entry : eventToken.events().entrySet()) {
            SsfScimOperation operation = SsfScimOperation.of(entry.getKey());
            if (operation != null) {
                Map<String, Object> payload = (entry.getValue() instanceof Map<?, ?> map) ? (Map<String, Object>) map
                        : Map.of();
                events.add(new SsfScimEvent(entry.getKey(), operation, subject, payload));
            }
        }
        return List.copyOf(events);
    }

    /**
     * Whether the payload carries the resource as {@link #data()}: the event type ends in
     * {@code :full}.
     */
    public boolean isFull() {
        return this.eventType.endsWith(":full");
    }

    /**
     * Whether the payload only names the modified {@link #attributes()}: the event type
     * ends in {@code :notice}. A receiver may fetch the resource from the SCIM service
     * provider with a GET of the subject's uri.
     */
    public boolean isNotice() {
        return this.eventType.endsWith(":notice");
    }

    /**
     * @return the {@code data} of a {@code full} event: the representation of the created
     * or replaced resource, or the {@code PatchOp} applied; {@code null} if absent
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> data() {
        return (this.payload.get("data") instanceof Map<?, ?> data) ? (Map<String, Object>) data : null;
    }

    /**
     * @return the {@code attributes} of a {@code notice} event: the paths of the
     * attributes created, modified or removed; empty if absent
     */
    public List<String> attributes() {
        if (!(this.payload.get("attributes") instanceof List<?> attributes)) {
            return List.of();
        }
        return attributes.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * @return the ETag {@code version} of the resource after the event, {@code null} if
     * absent
     */
    public String version() {
        return string("version");
    }

    /**
     * @return the HTTP {@code method} of the request an
     * {@link SsfScimOperation#ASYNC_RESPONSE} reports on, {@code null} for other events
     */
    public String method() {
        return string("method");
    }

    /**
     * @return the HTTP {@code status} of the request an
     * {@link SsfScimOperation#ASYNC_RESPONSE} reports on, as a string like the SCIM bulk
     * response has it; {@code null} for other events
     */
    public String status() {
        return string("status");
    }

    /**
     * @return the SCIM error {@code response} of a failed request an
     * {@link SsfScimOperation#ASYNC_RESPONSE} reports on, with {@code scimType} and
     * {@code detail}; {@code null} if the request succeeded or for other events
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> response() {
        return (this.payload.get("response") instanceof Map<?, ?> response) ? (Map<String, Object>) response : null;
    }

    private String string(String name) {
        Object value = this.payload.get(name);
        return (value != null) ? value.toString() : null;
    }

}

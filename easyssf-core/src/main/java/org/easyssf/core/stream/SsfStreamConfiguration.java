package org.easyssf.core.stream;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SsfEventTypes;

/**
 * The configuration of an event stream (SSF 1.0, section 8.1.1), as sent to and returned
 * by the transmitter.
 *
 * @param claims the members of the stream configuration
 */
public record SsfStreamConfiguration(Map<String, Object> claims) {

    /**
     * A stream the transmitter pushes events to.
     * @param endpointUrl the push endpoint of the receiver, as the transmitter reaches it
     * @param authorizationHeader the {@code Authorization} header the transmitter has to
     * send, may be {@code null}
     * @param eventsRequested the requested event types (alias or URI)
     * @param description describes the stream, may be {@code null}
     */
    public static SsfStreamConfiguration push(URI endpointUrl, String authorizationHeader,
            Collection<String> eventsRequested, String description) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("method", SsfDeliveryMethod.PUSH.uri());
        delivery.put("endpoint_url", endpointUrl.toString());
        if (authorizationHeader != null) {
            delivery.put("authorization_header", authorizationHeader);
        }
        return create(delivery, eventsRequested, description);
    }

    /**
     * A stream the receiver polls events from.
     * @param eventsRequested the requested event types (alias or URI)
     * @param description describes the stream, may be {@code null}
     */
    public static SsfStreamConfiguration poll(Collection<String> eventsRequested, String description) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("method", SsfDeliveryMethod.POLL.uri());
        return create(delivery, eventsRequested, description);
    }

    private static SsfStreamConfiguration create(Map<String, Object> delivery, Collection<String> eventsRequested,
            String description) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("delivery", delivery);
        claims.put("events_requested", eventsRequested.stream().map(SsfEventTypes::resolve).toList());
        if (description != null) {
            claims.put("description", description);
        }
        return new SsfStreamConfiguration(claims);
    }

    public String streamId() {
        return (this.claims.get("stream_id") instanceof String value) ? value : null;
    }

    public String issuer() {
        return (this.claims.get("iss") instanceof String value) ? value : null;
    }

    /**
     * The audiences of the SETs of this stream, empty if unknown.
     */
    public List<String> audience() {
        Object audience = this.claims.get("aud");
        return (audience instanceof String value) ? List.of(value) : strings(audience);
    }

    public List<String> eventsSupported() {
        return strings(this.claims.get("events_supported"));
    }

    public List<String> eventsRequested() {
        return strings(this.claims.get("events_requested"));
    }

    public List<String> eventsDelivered() {
        return strings(this.claims.get("events_delivered"));
    }

    public String description() {
        return (this.claims.get("description") instanceof String value) ? value : null;
    }

    /**
     * The identifier of the delivery method, see {@link SsfDeliveryMethod#uri()}.
     */
    public String deliveryMethod() {
        return (delivery().get("method") instanceof String value) ? value : null;
    }

    /**
     * The push endpoint of the receiver or the poll endpoint of the transmitter.
     */
    public URI deliveryEndpointUrl() {
        return (delivery().get("endpoint_url") instanceof String value && !value.isBlank()) ? URI.create(value) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> delivery() {
        return (this.claims.get("delivery") instanceof Map<?, ?> delivery) ? (Map<String, Object>) delivery : Map.of();
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

}

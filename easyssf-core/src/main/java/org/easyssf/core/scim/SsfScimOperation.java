package org.easyssf.core.scim;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.easyssf.core.event.SsfEventTypes;

/**
 * What a SCIM Event (RFC 9967) reports, independent of whether its payload carries the
 * resource ({@code full}) or only names the attributes that changed ({@code notice}).
 */
public enum SsfScimOperation {

    /**
     * The resource joined the event feed (section 2.3.1), it is not necessarily new.
     */
    FEED_ADD(SsfEventTypes.SCIM_FEED_ADD),

    /**
     * The resource left the event feed (section 2.3.2), it was not necessarily deleted.
     */
    FEED_REMOVE(SsfEventTypes.SCIM_FEED_REMOVE),

    /**
     * The resource was created (section 2.4.1).
     */
    CREATE(SsfEventTypes.SCIM_PROV_CREATE_NOTICE, SsfEventTypes.SCIM_PROV_CREATE_FULL),

    /**
     * The resource was modified with SCIM PATCH (section 2.4.2).
     */
    PATCH(SsfEventTypes.SCIM_PROV_PATCH_NOTICE, SsfEventTypes.SCIM_PROV_PATCH_FULL),

    /**
     * The resource was replaced with SCIM PUT (section 2.4.3).
     */
    PUT(SsfEventTypes.SCIM_PROV_PUT_NOTICE, SsfEventTypes.SCIM_PROV_PUT_FULL),

    /**
     * The resource was deleted, and thereby removed from the feed (section 2.4.4).
     */
    DELETE(SsfEventTypes.SCIM_PROV_DELETE),

    /**
     * The resource was activated, for example its account may be logged in to (section
     * 2.4.5).
     */
    ACTIVATE(SsfEventTypes.SCIM_PROV_ACTIVATE),

    /**
     * The resource was deactivated, typically its user may no longer have an active
     * session (section 2.4.6).
     */
    DEACTIVATE(SsfEventTypes.SCIM_PROV_DEACTIVATE),

    /**
     * An asynchronous SCIM request completed (section 2.5.1.3).
     */
    ASYNC_RESPONSE(SsfEventTypes.SCIM_MISC_ASYNC_RESPONSE);

    private static final Map<String, SsfScimOperation> BY_EVENT_TYPE = Arrays.stream(values())
        .flatMap((operation) -> operation.eventTypes.stream().map((eventType) -> Map.entry(eventType, operation)))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final List<String> eventTypes;

    SsfScimOperation(String... eventTypes) {
        this.eventTypes = List.of(eventTypes);
    }

    /**
     * @return the event type URIs that report this operation, one or, for the
     * provisioning operations with a {@code notice} and a {@code full} variant, two
     */
    public List<String> eventTypes() {
        return this.eventTypes;
    }

    /**
     * Whether this is one of the provisioning operations of section 2.4, which report a
     * change of the resource at the SCIM service provider.
     */
    public boolean isProvisioning() {
        return this != FEED_ADD && this != FEED_REMOVE && this != ASYNC_RESPONSE;
    }

    /**
     * @param eventType an event type URI or alias
     * @return the operation the event type reports, {@code null} if it is not a SCIM
     * Event
     */
    public static SsfScimOperation of(String eventType) {
        return (eventType != null) ? BY_EVENT_TYPE.get(SsfEventTypes.resolve(eventType)) : null;
    }

    /**
     * @return whether the event type is one of SCIM Events
     */
    public static boolean isScimEvent(String eventType) {
        return of(eventType) != null;
    }

}

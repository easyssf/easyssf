package org.easyssf.receiver.scim;

import java.util.List;

import org.easyssf.core.scim.SsfScimEvent;
import org.easyssf.core.scim.SsfScimSubject;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;

/**
 * An {@link SsfEventHandler} for SCIM Events (RFC 9967) that dispatches every SCIM Event
 * of a SET to the method of its operation: {@link #onCreate}, {@link #onPatch},
 * {@link #onPut}, {@link #onDelete}, {@link #onActivate}, {@link #onDeactivate},
 * {@link #onFeedAdd}, {@link #onFeedRemove} and {@link #onAsyncResponse}. The methods do
 * nothing by default, override the ones the receiver acts on. SETs without SCIM Events
 * are ignored.
 *
 * <p>
 * Each event comes with its {@link SsfScimEvent#subject() subject}, the SCIM resource it
 * is about, and the {@code SsfEventContext} of the SET for its {@code jti}, {@code iss}
 * and {@code txn}. Like every handler, an implementation has to be idempotent: a SET is
 * delivered again if any handler failed. The {@code txn} claim identifies the underlying
 * transaction across such retransmissions.
 *
 * <pre>
 * class ProvisioningHandler extends SsfScimEventHandler {
 *
 *     &#64;Override
 *     protected void onCreate(SsfScimEvent event, SsfEventContext eventContext) {
 *         if (event.isFull() &amp;&amp; "Users".equals(event.subject().resourceType())) {
 *             users.create(event.subject().id(), event.data());
 *         }
 *     }
 *
 *     &#64;Override
 *     protected void onDeactivate(SsfScimEvent event, SsfEventContext eventContext) {
 *         users.disable(event.subject().id());
 *     }
 *
 * }
 * </pre>
 */
public abstract class SsfScimEventHandler implements SsfEventHandler {

    @Override
    public void handle(SsfEventContext eventContext) {
        List<SsfScimEvent> events = SsfScimEvent.in(eventContext.eventToken());
        for (SsfScimEvent event : events) {
            onScimEvent(event, eventContext);
        }
    }

    /**
     * Called for every SCIM Event of a SET, dispatches to the method of its operation.
     * Override to treat all SCIM Events alike, or to refuse events without a
     * {@link SsfScimSubject SCIM subject}.
     * @param event the event
     * @param eventContext the SET the event arrived in
     */
    protected void onScimEvent(SsfScimEvent event, SsfEventContext eventContext) {
        switch (event.operation()) {
            case FEED_ADD -> onFeedAdd(event, eventContext);
            case FEED_REMOVE -> onFeedRemove(event, eventContext);
            case CREATE -> onCreate(event, eventContext);
            case PATCH -> onPatch(event, eventContext);
            case PUT -> onPut(event, eventContext);
            case DELETE -> onDelete(event, eventContext);
            case ACTIVATE -> onActivate(event, eventContext);
            case DEACTIVATE -> onDeactivate(event, eventContext);
            case ASYNC_RESPONSE -> onAsyncResponse(event, eventContext);
        }
    }

    /**
     * The resource joined the event feed ({@code feed:add}).
     */
    protected void onFeedAdd(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource left the event feed ({@code feed:remove}).
     */
    protected void onFeedRemove(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource was created ({@code prov:create:notice} or {@code prov:create:full}).
     */
    protected void onCreate(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource was modified with SCIM PATCH ({@code prov:patch:notice} or
     * {@code prov:patch:full}).
     */
    protected void onPatch(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource was replaced with SCIM PUT ({@code prov:put:notice} or
     * {@code prov:put:full}).
     */
    protected void onPut(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource was deleted ({@code prov:delete}).
     */
    protected void onDelete(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource was activated ({@code prov:activate}).
     */
    protected void onActivate(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * The resource was deactivated ({@code prov:deactivate}).
     */
    protected void onDeactivate(SsfScimEvent event, SsfEventContext eventContext) {
    }

    /**
     * An asynchronous SCIM request completed ({@code misc:asyncresp}).
     */
    protected void onAsyncResponse(SsfScimEvent event, SsfEventContext eventContext) {
    }

}

package org.easyssf.receiver.caep;

import org.easyssf.core.caep.SsfCaepEvent;
import org.easyssf.core.caep.SsfCaepEventKind;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;

/**
 * An {@link SsfEventHandler} for CAEP 1.0 events that dispatches every CAEP event of a
 * SET to the method of its kind: {@link #onSessionRevoked}, {@link #onTokenClaimsChange},
 * {@link #onCredentialChange}, {@link #onAssuranceLevelChange},
 * {@link #onDeviceComplianceChange}, {@link #onSessionEstablished},
 * {@link #onSessionPresented}, {@link #onRiskLevelChange}, and {@link #onOtherCaepEvent}
 * for an event type in the CAEP namespace this version does not know. The methods do
 * nothing by default, override the ones the receiver acts on. SETs without CAEP events
 * are ignored.
 *
 * <p>
 * Each event comes with typed access to its payload and with the {@code SsfEventContext}
 * of the SET, whose {@link SsfEventContext#subject() subject} the event is about. The
 * built-in reactions to {@code session-revoked} and {@code credential-change}, ending
 * sessions and rejecting tokens, are {@code SsfSessionTerminationEventHandler} and
 * {@code SsfTokenRevocationEventHandler}; this handler is for what the application does
 * on top. Like every handler, an implementation has to be idempotent: a SET is delivered
 * again if any handler failed.
 *
 * <pre>
 * class StepUpHandler extends SsfCaepEventHandler {
 *
 *     &#64;Override
 *     protected void onAssuranceLevelChange(SsfCaepEvent event, SsfEventContext eventContext) {
 *         if ("decrease".equals(event.changeDirection())) {
 *             stepUp.require(eventContext.subject().subject(), event.currentLevel());
 *         }
 *     }
 *
 * }
 * </pre>
 */
public abstract class SsfCaepEventHandler implements SsfEventHandler {

    @Override
    public void handle(SsfEventContext eventContext) {
        for (SsfCaepEvent event : SsfCaepEvent.in(eventContext.eventToken())) {
            onCaepEvent(event, eventContext);
        }
    }

    /**
     * Called for every CAEP event of a SET, dispatches to the method of its kind.
     * Override to treat all CAEP events alike.
     * @param event the event
     * @param eventContext the SET the event arrived in
     */
    protected void onCaepEvent(SsfCaepEvent event, SsfEventContext eventContext) {
        switch (event.kind()) {
            case SESSION_REVOKED -> onSessionRevoked(event, eventContext);
            case TOKEN_CLAIMS_CHANGE -> onTokenClaimsChange(event, eventContext);
            case CREDENTIAL_CHANGE -> onCredentialChange(event, eventContext);
            case ASSURANCE_LEVEL_CHANGE -> onAssuranceLevelChange(event, eventContext);
            case DEVICE_COMPLIANCE_CHANGE -> onDeviceComplianceChange(event, eventContext);
            case SESSION_ESTABLISHED -> onSessionEstablished(event, eventContext);
            case SESSION_PRESENTED -> onSessionPresented(event, eventContext);
            case RISK_LEVEL_CHANGE -> onRiskLevelChange(event, eventContext);
            case OTHER -> onOtherCaepEvent(event, eventContext);
        }
    }

    /**
     * The session was revoked ({@code CaepSessionRevoked}).
     */
    protected void onSessionRevoked(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * Claims of the token changed ({@code CaepTokenClaimsChange}), see
     * {@link SsfCaepEvent#claims()}.
     */
    protected void onTokenClaimsChange(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * A credential was created, revoked, updated or deleted
     * ({@code CaepCredentialChange}), see {@link SsfCaepEvent#credentialType()} and
     * {@link SsfCaepEvent#changeType()}.
     */
    protected void onCredentialChange(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * The assurance level changed ({@code CaepAssuranceLevelChange}), see
     * {@link SsfCaepEvent#currentLevel()} and {@link SsfCaepEvent#changeDirection()}.
     */
    protected void onAssuranceLevelChange(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * The compliance status of the device changed ({@code CaepDeviceComplianceChange}),
     * see {@link SsfCaepEvent#currentStatus()}.
     */
    protected void onDeviceComplianceChange(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * A session was established ({@code CaepSessionEstablished}).
     */
    protected void onSessionEstablished(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * A session was presented ({@code CaepSessionPresented}).
     */
    protected void onSessionPresented(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * The risk level changed ({@code CaepRiskLevelChange}), see
     * {@link SsfCaepEvent#currentLevel()} and {@link SsfCaepEvent#principal()}.
     */
    protected void onRiskLevelChange(SsfCaepEvent event, SsfEventContext eventContext) {
    }

    /**
     * An event in the CAEP namespace this version of easyssf does not know
     * ({@link SsfCaepEventKind#OTHER}): the common claims of CAEP section 2 are typed,
     * the rest is in {@link SsfCaepEvent#payload()}.
     */
    protected void onOtherCaepEvent(SsfCaepEvent event, SsfEventContext eventContext) {
    }

}

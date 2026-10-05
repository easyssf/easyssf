package org.easyssf.receiver.risc;

import org.easyssf.core.risc.SsfRiscEvent;
import org.easyssf.core.risc.SsfRiscEventKind;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;

/**
 * An {@link SsfEventHandler} for RISC 1.0 events that dispatches every RISC event of a
 * SET to the method of its kind: {@link #onAccountCredentialChangeRequired},
 * {@link #onAccountPurged}, {@link #onAccountDisabled}, {@link #onAccountEnabled},
 * {@link #onIdentifierChanged}, {@link #onIdentifierRecycled},
 * {@link #onCredentialCompromise}, {@link #onOptIn}, {@link #onOptOutInitiated},
 * {@link #onOptOutCancelled}, {@link #onOptOutEffective}, {@link #onRecoveryActivated},
 * {@link #onRecoveryInformationChanged}, {@link #onSessionsRevoked}, and
 * {@link #onOtherRiscEvent} for an event type in the RISC namespace this version does not
 * know. The methods do nothing by default, override the ones the receiver acts on. SETs
 * without RISC events are ignored.
 *
 * <p>
 * Each event comes with the {@code SsfEventContext} of the SET, whose
 * {@link SsfEventContext#subject() subject} is the account the event is about. Like every
 * handler, an implementation has to be idempotent: a SET is delivered again if any
 * handler failed.
 *
 * <pre>
 * class AccountHandler extends SsfRiscEventHandler {
 *
 *     &#64;Override
 *     protected void onAccountDisabled(SsfRiscEvent event, SsfEventContext eventContext) {
 *         accounts.suspend(eventContext.subject().email(), event.reason());
 *     }
 *
 *     &#64;Override
 *     protected void onAccountPurged(SsfRiscEvent event, SsfEventContext eventContext) {
 *         accounts.delete(eventContext.subject().email());
 *     }
 *
 * }
 * </pre>
 */
public abstract class SsfRiscEventHandler implements SsfEventHandler {

    @Override
    public void handle(SsfEventContext eventContext) {
        for (SsfRiscEvent event : SsfRiscEvent.in(eventContext.eventToken())) {
            onRiscEvent(event, eventContext);
        }
    }

    /**
     * Called for every RISC event of a SET, dispatches to the method of its kind.
     * Override to treat all RISC events alike.
     * @param event the event
     * @param eventContext the SET the event arrived in
     */
    protected void onRiscEvent(SsfRiscEvent event, SsfEventContext eventContext) {
        switch (event.kind()) {
            case ACCOUNT_CREDENTIAL_CHANGE_REQUIRED -> onAccountCredentialChangeRequired(event, eventContext);
            case ACCOUNT_PURGED -> onAccountPurged(event, eventContext);
            case ACCOUNT_DISABLED -> onAccountDisabled(event, eventContext);
            case ACCOUNT_ENABLED -> onAccountEnabled(event, eventContext);
            case IDENTIFIER_CHANGED -> onIdentifierChanged(event, eventContext);
            case IDENTIFIER_RECYCLED -> onIdentifierRecycled(event, eventContext);
            case CREDENTIAL_COMPROMISE -> onCredentialCompromise(event, eventContext);
            case OPT_IN -> onOptIn(event, eventContext);
            case OPT_OUT_INITIATED -> onOptOutInitiated(event, eventContext);
            case OPT_OUT_CANCELLED -> onOptOutCancelled(event, eventContext);
            case OPT_OUT_EFFECTIVE -> onOptOutEffective(event, eventContext);
            case RECOVERY_ACTIVATED -> onRecoveryActivated(event, eventContext);
            case RECOVERY_INFORMATION_CHANGED -> onRecoveryInformationChanged(event, eventContext);
            case SESSIONS_REVOKED -> onSessionsRevoked(event, eventContext);
            case OTHER -> onOtherRiscEvent(event, eventContext);
        }
    }

    /**
     * The account has to change its credential
     * ({@code RiscAccountCredentialChangeRequired}).
     */
    protected void onAccountCredentialChangeRequired(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The account was permanently deleted ({@code RiscAccountPurged}).
     */
    protected void onAccountPurged(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The account was disabled ({@code RiscAccountDisabled}), see
     * {@link SsfRiscEvent#reason()}.
     */
    protected void onAccountDisabled(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The account was enabled again ({@code RiscAccountEnabled}).
     */
    protected void onAccountEnabled(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The identifier of the account changed ({@code RiscIdentifierChanged}), see
     * {@link SsfRiscEvent#newValue()}.
     */
    protected void onIdentifierChanged(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The identifier was given to someone else ({@code RiscIdentifierRecycled}).
     */
    protected void onIdentifierRecycled(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * A credential was found compromised ({@code RiscCredentialCompromise}), see
     * {@link SsfRiscEvent#credentialType()}.
     */
    protected void onCredentialCompromise(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The account takes part in the RISC exchange ({@code RiscOptIn}).
     */
    protected void onOptIn(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The user asked to leave the RISC exchange ({@code RiscOptOutInitiated}).
     */
    protected void onOptOutInitiated(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The user withdrew the request to leave ({@code RiscOptOutCancelled}).
     */
    protected void onOptOutCancelled(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The account left the RISC exchange ({@code RiscOptOutEffective}).
     */
    protected void onOptOutEffective(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The account went through a recovery flow ({@code RiscRecoveryActivated}).
     */
    protected void onRecoveryActivated(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * The recovery information changed ({@code RiscRecoveryInformationChanged}).
     */
    protected void onRecoveryInformationChanged(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * All sessions of the account were revoked ({@code RiscSessionsRevoked}, deprecated
     * by RISC in favour of {@code CaepSessionRevoked}).
     */
    protected void onSessionsRevoked(SsfRiscEvent event, SsfEventContext eventContext) {
    }

    /**
     * An event in the RISC namespace this version of easyssf does not know
     * ({@link SsfRiscEventKind#OTHER}); its attributes are in
     * {@link SsfRiscEvent#payload()}.
     */
    protected void onOtherRiscEvent(SsfRiscEvent event, SsfEventContext eventContext) {
    }

}

package org.easyssf.receiver.stream;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates stream verification events (SSF 1.0, section 8.1.4.1) and keeps track of the
 * verifications this receiver requested.
 *
 * <p>
 * A verification event has to be about the stream of the receiver: its {@code sub_id} is
 * an {@code opaque} identifier with the identifier of the stream. If it echoes a
 * {@code state}, that has to be a state the receiver sent with a verification request
 * that was not answered yet. An event without {@code state} was initiated by the
 * transmitter and is accepted.
 */
public class SsfStreamVerification {

    private static final Logger logger = LoggerFactory.getLogger(SsfStreamVerification.class);

    private final Set<String> pendingStates = ConcurrentHashMap.newKeySet();

    private Supplier<String> streamId = () -> null;

    private boolean rejectUnrequestedState;

    /**
     * Sets a supplier of the identifier of the stream of this receiver, {@code null} as
     * long as it is not known. The subject of verification events is not checked while it
     * is unknown.
     */
    public void setStreamId(Supplier<String> streamId) {
        SsfAssert.notNull(streamId, "streamId must not be null");
        this.streamId = streamId;
    }

    /**
     * Whether a verification event with a {@code state} is rejected when the receiver is
     * not waiting for the answer to a verification request. By default such an event is
     * accepted, since some transmitters add a state to the verification events they
     * initiate themselves.
     */
    public void setRejectUnrequestedState(boolean rejectUnrequestedState) {
        this.rejectUnrequestedState = rejectUnrequestedState;
    }

    /**
     * Creates a state for a verification request and remembers it until the verification
     * event that echoes it arrived.
     */
    public String newState() {
        String state = UUID.randomUUID().toString();
        this.pendingStates.add(state);
        return state;
    }

    /**
     * Asks the transmitter to send a verification event on the stream.
     * @return the state the verification event has to echo
     */
    public String requestVerification(SsfStreamClient streamClient, String streamId) {
        String state = newState();
        try {
            streamClient.requestVerification(streamId, state);
        }
        catch (RuntimeException ex) {
            this.pendingStates.remove(state);
            throw ex;
        }
        return state;
    }

    /**
     * Whether a verification request was sent that has not been answered yet.
     */
    public boolean isPending() {
        return !this.pendingStates.isEmpty();
    }

    /**
     * Validates the verification event of a SET, if it has one.
     * @throws SsfSetVerificationException if the event is about another stream or echoes
     * a state that is not expected
     */
    public void validate(SsfEventContext eventContext) {
        if (!eventContext.hasEvent(SsfEventTypes.SSF_STREAM_VERIFICATION)) {
            return;
        }
        String expectedStreamId = this.streamId.get();
        Map<String, Object> subjectId = eventContext.eventToken().subjectId();
        if (expectedStreamId != null && subjectId != null
                && !("opaque".equals(subjectId.get("format")) && expectedStreamId.equals(subjectId.get("id")))) {
            throw new SsfSetVerificationException(SsfSetVerificationException.INVALID_REQUEST,
                    "The verification event is not about the stream of this receiver");
        }
        Map<String, Object> event = eventContext.eventFor(SsfEventTypes.SSF_STREAM_VERIFICATION);
        Object state = (event != null) ? event.get("state") : null;
        if (state == null || this.pendingStates.remove(state)) {
            return;
        }
        if (!this.pendingStates.isEmpty() || this.rejectUnrequestedState) {
            throw new SsfSetVerificationException(SsfSetVerificationException.INVALID_STATE,
                    "The state of the verification event is not the one this receiver sent");
        }
        logger.debug("Accepting a verification event with a state although no verification was requested");
    }

}

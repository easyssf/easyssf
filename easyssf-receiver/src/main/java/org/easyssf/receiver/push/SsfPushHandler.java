package org.easyssf.receiver.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.event.SsfEventHandlingException;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.set.SsfSetClaims;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Handles SETs delivered using HTTP push (RFC 8935), independent of the web framework:
 * call {@link #handle} from the endpoint the transmitter posts SETs to and send the
 * returned response.
 *
 * <p>
 * A SET is verified and handled before it is acknowledged with {@code 202 Accepted}.
 * Invalid SETs are rejected with {@code 400 Bad Request} and an error document. When the
 * SET could not be processed for a reason the transmitter is not responsible for (its
 * keys are temporarily not retrievable, an event handler failed) a {@code 5xx} status
 * asks it to deliver the SET again.
 */
public class SsfPushHandler {

    /**
     * The maximum size of a SET in bytes. An endpoint does not need to read more than one
     * byte beyond it.
     */
    public static final int MAX_SET_SIZE = 256 * 1024;

    private static final Logger logger = LoggerFactory.getLogger(SsfPushHandler.class);

    private static final String AUTHENTICATION_FAILED = "authentication_failed";

    private final SsfSetProcessor processor;

    private final Function<String, String> expectedAuthorizationHeaders;

    private final boolean headerDependsOnIssuer;

    private SsfReceiverMetrics metrics = SsfReceiverMetrics.NOOP;

    /**
     * @param processor processes the received SETs
     * @param expectedAuthorizationHeader the exact {@code Authorization} header the
     * transmitter must send, {@code null} to not check the header
     */
    public SsfPushHandler(SsfSetProcessor processor, String expectedAuthorizationHeader) {
        SsfAssert.notNull(processor, "processor must not be null");
        this.processor = processor;
        this.expectedAuthorizationHeaders = (issuer) -> expectedAuthorizationHeader;
        this.headerDependsOnIssuer = false;
    }

    /**
     * For a receiver with several transmitters: the {@code Authorization} header a
     * transmitter has to send is looked up by the issuer of the SET, which is read before
     * the SET is verified.
     * @param processor processes the SETs
     * @param expectedAuthorizationHeaders the exact {@code Authorization} header the
     * transmitter with an issuer has to send, {@code null} if none is expected from it
     */
    public SsfPushHandler(SsfSetProcessor processor, Function<String, String> expectedAuthorizationHeaders) {
        SsfAssert.notNull(processor, "processor must not be null");
        SsfAssert.notNull(expectedAuthorizationHeaders, "expectedAuthorizationHeaders must not be null");
        this.processor = processor;
        this.expectedAuthorizationHeaders = expectedAuthorizationHeaders;
        this.headerDependsOnIssuer = true;
    }

    public void setMetrics(SsfReceiverMetrics metrics) {
        SsfAssert.notNull(metrics, "metrics must not be null");
        this.metrics = metrics;
    }

    /**
     * Handles a push request.
     * @param authorizationHeader the {@code Authorization} header of the request, may be
     * {@code null}
     * @param body the body of the request, the SET in JWS compact serialization
     * @return the response to send to the transmitter
     */
    public SsfPushResponse handle(String authorizationHeader, byte[] body) {
        if (!this.headerDependsOnIssuer && !isAuthenticated(authorizationHeader, null)) {
            this.metrics.setReceived(SsfDeliveryMethod.PUSH, SsfReceiverMetrics.SetOutcome.UNAUTHENTICATED);
            return error(401, AUTHENTICATION_FAILED, "The transmitter could not be authenticated");
        }
        if (body == null || body.length > MAX_SET_SIZE) {
            return error(413, SsfSetVerificationException.INVALID_REQUEST, "The SET is too large");
        }
        String encodedSet = new String(body, StandardCharsets.UTF_8).strip();
        if (this.headerDependsOnIssuer) {
            String issuer = SsfSetClaims.unverifiedIssuer(encodedSet);
            if (!isAuthenticated(authorizationHeader, issuer)) {
                this.metrics.setReceived(issuer, SsfDeliveryMethod.PUSH, SsfReceiverMetrics.SetOutcome.UNAUTHENTICATED);
                return error(401, AUTHENTICATION_FAILED, "The transmitter could not be authenticated");
            }
        }
        try {
            this.processor.process(encodedSet, SsfDeliveryMethod.PUSH);
        }
        catch (SsfSetVerificationException ex) {
            logger.debug("Rejecting SET: " + ex.getMessage(), ex);
            return error(400, ex.getErrorCode(), ex.getMessage());
        }
        catch (SsfTransmitterUnavailableException ex) {
            logger.warn("Could not verify SET, asking the transmitter to deliver it again: " + ex.getMessage());
            logger.debug("Cause of the failed SET verification", ex);
            return new SsfPushResponse(503, null);
        }
        catch (SsfEventHandlingException ex) {
            // already logged per handler
            return new SsfPushResponse(500, null);
        }
        return new SsfPushResponse(202, null);
    }

    private boolean isAuthenticated(String authorizationHeader, String issuer) {
        String expected = this.expectedAuthorizationHeaders.apply(issuer);
        if (expected == null) {
            return true;
        }
        return authorizationHeader != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                authorizationHeader.getBytes(StandardCharsets.UTF_8));
    }

    private static SsfPushResponse error(int status, String errorCode, String description) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("err", errorCode);
        if (description != null) {
            error.put("description", description);
        }
        return new SsfPushResponse(status, JSONObjectUtils.toJSONString(error));
    }

}

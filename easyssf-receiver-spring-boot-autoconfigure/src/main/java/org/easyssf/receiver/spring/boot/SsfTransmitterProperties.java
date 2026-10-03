package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.event.SubjectCompatibilityMode;

/**
 * The settings of one SSF transmitter. {@link SsfReceiverProperties} binds them at
 * {@code easyssf.receiver.*} for the default transmitter, and at
 * {@code easyssf.receiver.transmitters.<name>.*} for every other one.
 */
public class SsfTransmitterProperties {

    /**
     * Issuer of the SSF transmitter. Every inbound SET must carry this value as its 'iss'
     * claim.
     */
    private String transmitterIssuer;

    /**
     * Location of the transmitter's SSF configuration metadata. Derived from the
     * transmitter issuer when not set.
     */
    private URI transmitterMetadataUrl;

    /**
     * Location of the JWK Set used to verify SET signatures. Discovered from the
     * transmitter metadata ('jwks_uri') when not set.
     */
    private URI transmitterJwksUrl;

    /**
     * Whether the transmitter issuer and the endpoints it publishes may use plain 'http'
     * on hosts other than loopback addresses. For test setups only, SSF requires 'https'.
     */
    private boolean allowInsecureHttp;

    /**
     * Audience this receiver is known as at the transmitter. When set, every inbound SET
     * must contain it in its 'aud' claim.
     */
    private String expectedAudience;

    /**
     * How the transmitter delivers SETs: 'push' to the endpoint of this application or
     * 'poll' from an endpoint of the transmitter.
     */
    private SsfDeliveryMethod deliveryMethod = SsfDeliveryMethod.PUSH;

    /**
     * Access token to authenticate with at the stream management and poll endpoints of
     * the transmitter, for transmitters that hand out long-lived tokens.
     */
    private String transmitterAccessToken;

    private final Oauth2 oauth2 = new Oauth2();

    private final Stream stream = new Stream();

    private final Poll poll = new Poll();

    private final SetValidation setValidation = new SetValidation();

    private final Push push = new Push();

    public String getTransmitterIssuer() {
        return this.transmitterIssuer;
    }

    public void setTransmitterIssuer(String transmitterIssuer) {
        this.transmitterIssuer = transmitterIssuer;
    }

    public URI getTransmitterMetadataUrl() {
        return this.transmitterMetadataUrl;
    }

    public void setTransmitterMetadataUrl(URI transmitterMetadataUrl) {
        this.transmitterMetadataUrl = transmitterMetadataUrl;
    }

    public boolean isAllowInsecureHttp() {
        return this.allowInsecureHttp;
    }

    public void setAllowInsecureHttp(boolean allowInsecureHttp) {
        this.allowInsecureHttp = allowInsecureHttp;
    }

    public URI getTransmitterJwksUrl() {
        return this.transmitterJwksUrl;
    }

    public void setTransmitterJwksUrl(URI transmitterJwksUrl) {
        this.transmitterJwksUrl = transmitterJwksUrl;
    }

    public String getExpectedAudience() {
        return this.expectedAudience;
    }

    public void setExpectedAudience(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    public SsfDeliveryMethod getDeliveryMethod() {
        return this.deliveryMethod;
    }

    public void setDeliveryMethod(SsfDeliveryMethod deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
    }

    public String getTransmitterAccessToken() {
        return this.transmitterAccessToken;
    }

    public void setTransmitterAccessToken(String transmitterAccessToken) {
        this.transmitterAccessToken = transmitterAccessToken;
    }

    public Oauth2 getOauth2() {
        return this.oauth2;
    }

    public Stream getStream() {
        return this.stream;
    }

    public Poll getPoll() {
        return this.poll;
    }

    public SetValidation getSetValidation() {
        return this.setValidation;
    }

    public Push getPush() {
        return this.push;
    }

    /**
     * OAuth2 client credentials to authenticate with at the stream management and poll
     * endpoints of the transmitter.
     */
    public static class Oauth2 {

        /**
         * Token endpoint to obtain access tokens from with the client credentials grant.
         */
        private URI tokenUri;

        /**
         * Client id of this receiver.
         */
        private String clientId;

        /**
         * Client secret of this receiver.
         */
        private String clientSecret;

        /**
         * Scopes to request.
         */
        private List<String> scopes = new ArrayList<>();

        /**
         * How the client authenticates at the token endpoint: 'basic'
         * (client_secret_basic) or 'post' (client_secret_post).
         */
        private ClientAuthenticationMethod clientAuthenticationMethod = ClientAuthenticationMethod.BASIC;

        /**
         * How long before its expiry an access token is renewed; never more than a
         * quarter of the token's lifetime.
         */
        private Duration expirySafetyWindow = Duration.ofSeconds(30);

        /**
         * Form parameters to send with the token request in addition to the grant, for
         * extensions of the token endpoint.
         */
        private final Map<String, String> additionalParameters = new LinkedHashMap<>();

        public URI getTokenUri() {
            return this.tokenUri;
        }

        public void setTokenUri(URI tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return this.clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public List<String> getScopes() {
            return this.scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }

        public ClientAuthenticationMethod getClientAuthenticationMethod() {
            return this.clientAuthenticationMethod;
        }

        public void setClientAuthenticationMethod(ClientAuthenticationMethod clientAuthenticationMethod) {
            this.clientAuthenticationMethod = clientAuthenticationMethod;
        }

        public Duration getExpirySafetyWindow() {
            return this.expirySafetyWindow;
        }

        public void setExpirySafetyWindow(Duration expirySafetyWindow) {
            this.expirySafetyWindow = expirySafetyWindow;
        }

        public Map<String, String> getAdditionalParameters() {
            return this.additionalParameters;
        }

        public enum ClientAuthenticationMethod {

            BASIC, POST

        }

    }

    /**
     * The event stream of this receiver at the transmitter.
     */
    public static class Stream {

        /**
         * Who manages the stream: with 'transmitter' the stream is created at the
         * transmitter, with 'receiver' this application looks its stream up on startup
         * and creates or updates it if necessary.
         */
        private Management management = Management.TRANSMITTER;

        /**
         * Identifier of a stream that was created at the transmitter. When set, the
         * stream is looked up on startup to learn its audience and poll endpoint.
         */
        private String id;

        /**
         * Event types (alias or URI) requested for a stream managed by the receiver.
         */
        private List<String> eventsRequested = new ArrayList<>(List.of("CaepSessionRevoked", "CaepCredentialChange"));

        /**
         * Description of a stream managed by the receiver.
         */
        private String description;

        /**
         * Whether a stream managed by the receiver is deleted at the transmitter when the
         * application stops.
         */
        private boolean deleteOnShutdown = false;

        public Management getManagement() {
            return this.management;
        }

        public void setManagement(Management management) {
            this.management = management;
        }

        public String getId() {
            return this.id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public List<String> getEventsRequested() {
            return this.eventsRequested;
        }

        public void setEventsRequested(List<String> eventsRequested) {
            this.eventsRequested = eventsRequested;
        }

        public String getDescription() {
            return this.description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isDeleteOnShutdown() {
            return this.deleteOnShutdown;
        }

        public void setDeleteOnShutdown(boolean deleteOnShutdown) {
            this.deleteOnShutdown = deleteOnShutdown;
        }

        public enum Management {

            TRANSMITTER, RECEIVER

        }

    }

    /**
     * POLL delivery (RFC 8936).
     */
    public static class Poll {

        /**
         * Poll endpoint of the transmitter. Taken from the stream configuration when not
         * set.
         */
        private URI endpointUrl;

        /**
         * Whether the transmitter is polled periodically. Otherwise the application polls
         * by calling SsfPoller.pollNow().
         */
        private boolean autoStartup = true;

        /**
         * Time between two polls.
         */
        private Duration interval = Duration.ofSeconds(30);

        /**
         * Time to wait before the first poll.
         */
        private Duration initialDelay = Duration.ofSeconds(1);

        /**
         * Maximum number of SETs to fetch with one request.
         */
        private int maxEvents = 100;

        private final RateLimit rateLimit = new RateLimit();

        public URI getEndpointUrl() {
            return this.endpointUrl;
        }

        public void setEndpointUrl(URI endpointUrl) {
            this.endpointUrl = endpointUrl;
        }

        public boolean isAutoStartup() {
            return this.autoStartup;
        }

        public void setAutoStartup(boolean autoStartup) {
            this.autoStartup = autoStartup;
        }

        public Duration getInterval() {
            return this.interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getInitialDelay() {
            return this.initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public int getMaxEvents() {
            return this.maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public RateLimit getRateLimit() {
            return this.rateLimit;
        }

        /**
         * How the poller reacts when the transmitter rate-limits the poll endpoint.
         */
        public static class RateLimit {

            /**
             * Pause after a '429 Too Many Requests' without a 'Retry-After' header. Not
             * set: poll again at the regular interval.
             */
            private Duration fallbackBackoff;

            /**
             * Longest pause a 'Retry-After' header or the fallback can cause.
             */
            private Duration maxBackoff = Duration.ofMinutes(5);

            public Duration getFallbackBackoff() {
                return this.fallbackBackoff;
            }

            public void setFallbackBackoff(Duration fallbackBackoff) {
                this.fallbackBackoff = fallbackBackoff;
            }

            public Duration getMaxBackoff() {
                return this.maxBackoff;
            }

            public void setMaxBackoff(Duration maxBackoff) {
                this.maxBackoff = maxBackoff;
            }

        }

    }

    public static class SetValidation {

        /**
         * JWS algorithms accepted for SET signatures. Defaults to RS256 as required by
         * the CAEP interoperability profile.
         */
        private List<String> acceptedAlgorithms = new ArrayList<>(List.of("RS256"));

        /**
         * Minimum RSA key size in bits for SET signing keys. Use 0 to disable the check.
         */
        private int minRsaKeySize = 2048;

        /**
         * Whether the SET must be explicitly typed with a 'typ' header of 'secevent+jwt'.
         */
        private boolean requireTypeHeader = true;

        /**
         * How strictly the subject of a SET is validated: 'strict-ssf-1-0' requires the
         * top-level 'sub_id' claim of SSF 1.0, 'legacy' accepts SETs of transmitters
         * following earlier drafts, which put the subject into the event payload.
         */
        private SubjectCompatibilityMode subjectCompatibility = SubjectCompatibilityMode.STRICT_SSF_1_0;

        /**
         * Tolerated clock skew when checking that a SET was not issued in the future.
         */
        private Duration clockSkew = Duration.ofSeconds(60);

        public List<String> getAcceptedAlgorithms() {
            return this.acceptedAlgorithms;
        }

        public void setAcceptedAlgorithms(List<String> acceptedAlgorithms) {
            this.acceptedAlgorithms = acceptedAlgorithms;
        }

        public int getMinRsaKeySize() {
            return this.minRsaKeySize;
        }

        public void setMinRsaKeySize(int minRsaKeySize) {
            this.minRsaKeySize = minRsaKeySize;
        }

        public boolean isRequireTypeHeader() {
            return this.requireTypeHeader;
        }

        public void setRequireTypeHeader(boolean requireTypeHeader) {
            this.requireTypeHeader = requireTypeHeader;
        }

        public SubjectCompatibilityMode getSubjectCompatibility() {
            return this.subjectCompatibility;
        }

        public void setSubjectCompatibility(SubjectCompatibilityMode subjectCompatibility) {
            this.subjectCompatibility = subjectCompatibility;
        }

        public Duration getClockSkew() {
            return this.clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

    }

    /**
     * PUSH delivery (RFC 8935) from this transmitter.
     */
    public static class Push {

        /**
         * Exact value the transmitter must send in the 'Authorization' header, for
         * example 'Bearer s3cr3t'. No header check is performed when not set.
         */
        private String expectedAuthHeader;

        /**
         * URL under which the transmitter reaches the push endpoint. Required for a
         * stream managed by the receiver.
         */
        private URI deliveryEndpointUrl;

        public String getExpectedAuthHeader() {
            return this.expectedAuthHeader;
        }

        public void setExpectedAuthHeader(String expectedAuthHeader) {
            this.expectedAuthHeader = expectedAuthHeader;
        }

        public URI getDeliveryEndpointUrl() {
            return this.deliveryEndpointUrl;
        }

        public void setDeliveryEndpointUrl(URI deliveryEndpointUrl) {
            this.deliveryEndpointUrl = deliveryEndpointUrl;
        }

    }

}

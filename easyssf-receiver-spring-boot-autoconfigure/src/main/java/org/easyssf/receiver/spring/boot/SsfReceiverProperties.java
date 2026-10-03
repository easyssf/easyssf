package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenID Shared Signals Framework (SSF) receiver.
 */
@ConfigurationProperties("easyssf.receiver")
public class SsfReceiverProperties {

    /**
     * Whether the SSF receiver is enabled.
     */
    private boolean enabled = true;

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
     * Aliases for event type URIs, e.g. 'AcmeLogin: https://events.acme.example/login',
     * usable wherever an event type is named. They add to the built-in aliases of the
     * SSF, CAEP and RISC event types and cannot redefine one.
     */
    private final Map<String, String> eventAliases = new LinkedHashMap<>();

    /**
     * Access token to authenticate with at the stream management and poll endpoints of
     * the transmitter, for transmitters that hand out long-lived tokens.
     */
    private String transmitterAccessToken;

    private final Oauth2 oauth2 = new Oauth2();

    private final Stream stream = new Stream();

    private final Poll poll = new Poll();

    private final Http http = new Http();

    private final Metrics metrics = new Metrics();

    private final Jdbc jdbc = new Jdbc();

    private final SetValidation setValidation = new SetValidation();

    private final Push push = new Push();

    private final Dedup dedup = new Dedup();

    private final ResourceServer resourceServer = new ResourceServer();

    private final OidcClient oidcClient = new OidcClient();

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public Map<String, String> getEventAliases() {
        return this.eventAliases;
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

    public Http getHttp() {
        return this.http;
    }

    public Metrics getMetrics() {
        return this.metrics;
    }

    public Jdbc getJdbc() {
        return this.jdbc;
    }

    public SetValidation getSetValidation() {
        return this.setValidation;
    }

    public Push getPush() {
        return this.push;
    }

    public Dedup getDedup() {
        return this.dedup;
    }

    public ResourceServer getResourceServer() {
        return this.resourceServer;
    }

    public OidcClient getOidcClient() {
        return this.oidcClient;
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

    }

    /**
     * Outbound HTTP calls to the transmitter.
     */
    public static class Http {

        /**
         * The timeout that applies when none is configured.
         */
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

        /**
         * Whether the transmitter is called with the RestClient of the application, if it
         * has a RestClient.Builder. Otherwise the HTTP client of the JDK is used.
         */
        private boolean useRestClient = true;

        /**
         * Connect timeout for calls to the transmitter. When not set, the timeout of the
         * HTTP clients of the application (spring.http.clients.connect-timeout) applies
         * to the RestClient, and 5 seconds otherwise.
         */
        private Duration connectTimeout;

        /**
         * Read timeout for calls to the transmitter. When not set, the timeout of the
         * HTTP clients of the application (spring.http.clients.read-timeout) applies to
         * the RestClient, and 5 seconds otherwise.
         */
        private Duration readTimeout;

        /**
         * User-Agent header for calls to the transmitter. The default of the JDK HTTP
         * client ('Java-http-client/<version>') is sent when not set.
         */
        private String userAgent;

        public boolean isUseRestClient() {
            return this.useRestClient;
        }

        public void setUseRestClient(boolean useRestClient) {
            this.useRestClient = useRestClient;
        }

        public Duration getConnectTimeout() {
            return this.connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return this.readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public String getUserAgent() {
            return this.userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

    }

    /**
     * Stores that keep the state of the receiver in the database of the application.
     */
    public static class Jdbc {

        /**
         * Whether processed SETs and revocations are kept in the database if the
         * application has a JdbcTemplate. Otherwise they are kept in memory.
         */
        private boolean enabled = true;

        /**
         * When to create the tables if they do not exist: 'embedded' for embedded
         * databases only, 'always' or 'never'.
         */
        private InitializeSchema initializeSchema = InitializeSchema.EMBEDDED;

        /**
         * Prefix of the names of the tables.
         */
        private String tablePrefix = "EASYSSF_";

        /**
         * How often expired rows (processed SETs past 'easyssf.receiver.dedup.retention',
         * revocations past 'easyssf.receiver.resource-server.revocation-ttl') are purged.
         * The stores also purge when they are written to; '0' turns the periodic cleanup
         * off.
         */
        private Duration cleanupInterval = Duration.ofMinutes(15);

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public InitializeSchema getInitializeSchema() {
            return this.initializeSchema;
        }

        public void setInitializeSchema(InitializeSchema initializeSchema) {
            this.initializeSchema = initializeSchema;
        }

        public String getTablePrefix() {
            return this.tablePrefix;
        }

        public void setTablePrefix(String tablePrefix) {
            this.tablePrefix = tablePrefix;
        }

        public Duration getCleanupInterval() {
            return this.cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }

        public enum InitializeSchema {

            EMBEDDED, ALWAYS, NEVER

        }

    }

    public static class Metrics {

        /**
         * Whether received SETs and polls are recorded with Micrometer, if the
         * application has a MeterRegistry.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

        public Duration getClockSkew() {
            return this.clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

    }

    /**
     * PUSH delivery (RFC 8935).
     */
    public static class Push {

        /**
         * Whether to expose the push endpoint when the delivery method is 'push'.
         */
        private boolean enabled = true;

        /**
         * Path of the push endpoint, relative to the DispatcherServlet.
         */
        private String endpointPath = "/ssf/push";

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

        private final Security security = new Security();

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpointPath() {
            return this.endpointPath;
        }

        public void setEndpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
        }

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

        public Security getSecurity() {
            return this.security;
        }

        public static class Security {

            /**
             * Whether to register a dedicated Spring Security filter chain that opens the
             * push endpoint (no CSRF, no session, no authentication). Disable to secure
             * the endpoint in your own filter chain.
             */
            private boolean enabled = true;

            public boolean isEnabled() {
                return this.enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

        }

    }

    /**
     * De-duplication of SETs by 'jti'.
     */
    public static class Dedup {

        /**
         * Whether SETs that were already processed are skipped.
         */
        private boolean enabled = true;

        /**
         * Number of SET identifiers the in-memory store remembers.
         */
        private int capacity = 10_000;

        /**
         * How long the JDBC store remembers a processed SET. Has to cover the time the
         * transmitter keeps trying to deliver a SET.
         */
        private Duration retention = Duration.ofDays(7);

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return this.capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public Duration getRetention() {
            return this.retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

    }

    /**
     * Resource server integration: rejects access tokens of revoked sessions.
     */
    public static class ResourceServer {

        /**
         * Whether access tokens are rejected once their session or subject was revoked.
         */
        private boolean enabled = true;

        /**
         * Event types (alias or URI) that revoke access tokens.
         */
        private List<String> eventTypes = new ArrayList<>(List.of("CaepSessionRevoked"));

        /**
         * How long the in-memory store remembers a revocation. Must be at least the
         * maximum access token lifetime.
         */
        private Duration revocationTtl = Duration.ofHours(1);

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getEventTypes() {
            return this.eventTypes;
        }

        public void setEventTypes(List<String> eventTypes) {
            this.eventTypes = eventTypes;
        }

        public Duration getRevocationTtl() {
            return this.revocationTtl;
        }

        public void setRevocationTtl(Duration revocationTtl) {
            this.revocationTtl = revocationTtl;
        }

    }

    /**
     * OIDC client integration: terminates local sessions of revoked users / sessions.
     */
    public static class OidcClient {

        /**
         * Whether local sessions are terminated when a matching event is received.
         */
        private boolean enabled = true;

        /**
         * Event types (alias or URI) that terminate the sessions identified by the
         * event's subject (session id and / or user).
         */
        private List<String> sessionEventTypes = new ArrayList<>(List.of("CaepSessionRevoked"));

        /**
         * Event types (alias or URI) that terminate all sessions of the user identified
         * by the event's subject.
         */
        private List<String> userEventTypes = new ArrayList<>(List.of("CaepCredentialChange"));

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getSessionEventTypes() {
            return this.sessionEventTypes;
        }

        public void setSessionEventTypes(List<String> sessionEventTypes) {
            this.sessionEventTypes = sessionEventTypes;
        }

        public List<String> getUserEventTypes() {
            return this.userEventTypes;
        }

        public void setUserEventTypes(List<String> userEventTypes) {
            this.userEventTypes = userEventTypes;
        }

    }

}

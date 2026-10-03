package org.easyssf.receiver.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenID Shared Signals Framework (SSF) receiver.
 *
 * <p>
 * The settings of a transmitter ({@link SsfTransmitterProperties}) at
 * {@code easyssf.receiver.*} configure the {@link SsfTransmitter#DEFAULT_NAME default}
 * transmitter; {@link #getTransmitters() easyssf.receiver.transmitters.<name>.*} adds
 * further ones, each complete on its own. Everything else is shared by all transmitters.
 */
@ConfigurationProperties("easyssf.receiver")
public class SsfReceiverProperties extends SsfTransmitterProperties {

    /**
     * Whether the SSF receiver is enabled.
     */
    private boolean enabled = true;

    /**
     * Aliases for event type URIs, e.g. 'AcmeLogin: https://events.acme.example/login',
     * usable wherever an event type is named. They add to the built-in aliases of the
     * SSF, CAEP and RISC event types and cannot redefine one.
     */
    private final Map<String, String> eventAliases = new LinkedHashMap<>();

    /**
     * Further transmitters by name, each with the settings of a transmitter. The settings
     * at easyssf.receiver.* configure the transmitter named 'default'.
     */
    private final Map<String, SsfTransmitterProperties> transmitters = new LinkedHashMap<>();

    private final Http http = new Http();

    private final Metrics metrics = new Metrics();

    private final Jdbc jdbc = new Jdbc();

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

    public Map<String, String> getEventAliases() {
        return this.eventAliases;
    }

    public Map<String, SsfTransmitterProperties> getTransmitters() {
        return this.transmitters;
    }

    /**
     * The transmitters that are configured, by name: the default one if its issuer is
     * set, and the named ones.
     * @throws IllegalStateException if a named transmitter is called 'default' while the
     * default transmitter is configured as well
     */
    public Map<String, SsfTransmitterProperties> getConfiguredTransmitters() {
        Map<String, SsfTransmitterProperties> configured = new LinkedHashMap<>();
        boolean hasDefault = getTransmitterIssuer() != null && !getTransmitterIssuer().isBlank();
        if (hasDefault) {
            configured.put(SsfTransmitter.DEFAULT_NAME, this);
        }
        this.transmitters.forEach((name, transmitter) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("A transmitter under easyssf.receiver.transmitters needs a name");
            }
            if (SsfTransmitter.DEFAULT_NAME.equals(name) && hasDefault) {
                throw new IllegalStateException("The transmitter named '" + SsfTransmitter.DEFAULT_NAME
                        + "' is the one configured at easyssf.receiver.*, it cannot be configured under "
                        + "easyssf.receiver.transmitters as well");
            }
            configured.put(name, transmitter);
        });
        return configured;
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

    @Override
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

    /**
     * The push endpoint of this application (RFC 8935), shared by all transmitters, and
     * PUSH delivery from the default transmitter.
     */
    public static class Push extends SsfTransmitterProperties.Push {

        /**
         * Whether to expose the push endpoint when a transmitter delivers by 'push'.
         */
        private boolean enabled = true;

        /**
         * Path of the push endpoint, relative to the DispatcherServlet.
         */
        private String endpointPath = "/ssf/push";

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

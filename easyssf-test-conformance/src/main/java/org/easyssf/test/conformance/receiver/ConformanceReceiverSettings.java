package org.easyssf.test.conformance.receiver;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;

/**
 * The settings of a receiver under test: the transmitter to use, how to authenticate at
 * it, the delivery, the stream and the limits of a run. A framework binds them from its
 * configuration (the Spring Boot receiver at {@code cts.*}) or sets them in code.
 */
public class ConformanceReceiverSettings {

    private final Transmitter transmitter = new Transmitter();

    private final Auth auth = new Auth();

    private final Delivery delivery = new Delivery();

    private final Stream stream = new Stream();

    private final Run run = new Run();

    public Transmitter getTransmitter() {
        return this.transmitter;
    }

    public Auth getAuth() {
        return this.auth;
    }

    public Delivery getDelivery() {
        return this.delivery;
    }

    public Stream getStream() {
        return this.stream;
    }

    public Run getRun() {
        return this.run;
    }

    public static class Transmitter {

        /**
         * Issuer of the emulated transmitter: the URL of the test instance in the
         * conformance suite, with its alias, e.g.
         * https://localhost.emobix.co.uk:8443/test/a/easyssf-receiver
         */
        private String issuer;

        /**
         * Name of the Spring SSL bundle (spring.ssl.bundle.*) whose trust store holds the
         * certificate of a conformance suite with a self-signed certificate.
         */
        private String sslBundle;

        /**
         * Whether to trust any TLS certificate of the transmitter instead. Only for a
         * conformance suite running locally.
         */
        private boolean trustAllCertificates = false;

        /**
         * Whether the host name of the transmitter has to match its certificate. The
         * self-signed certificate of a locally running conformance suite is issued for
         * 'localhost', not for 'localhost.emobix.co.uk'.
         */
        private boolean verifyHostname = true;

        public String getIssuer() {
            return this.issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getSslBundle() {
            return this.sslBundle;
        }

        public void setSslBundle(String sslBundle) {
            this.sslBundle = sslBundle;
        }

        public boolean isTrustAllCertificates() {
            return this.trustAllCertificates;
        }

        public void setTrustAllCertificates(boolean trustAllCertificates) {
            this.trustAllCertificates = trustAllCertificates;
        }

        public boolean isVerifyHostname() {
            return this.verifyHostname;
        }

        public void setVerifyHostname(boolean verifyHostname) {
            this.verifyHostname = verifyHostname;
        }

    }

    public static class Auth {

        /**
         * 'dynamic' obtains an access token with the client credentials grant from the
         * token endpoint of the emulated transmitter, 'static' sends the configured
         * access token. Matches the 'Authentication Variant' of the test plan.
         */
        private Mode mode = Mode.DYNAMIC;

        /**
         * Client id, as configured in the test plan (client.client_id).
         */
        private String clientId = "easyssf-receiver";

        /**
         * Client secret, as configured in the test plan (client.client_secret).
         */
        private String clientSecret = "easyssf-receiver-secret";

        /**
         * Scopes to request, as configured in the test plan (client.scope).
         */
        private List<String> scopes = new ArrayList<>(List.of("ssf.read", "ssf.manage"));

        /**
         * How the client authenticates at the token endpoint: 'basic' or 'post'. Matches
         * the 'Client Authentication Type' of the test plan.
         */
        private ClientAuthenticationMethod clientAuthenticationMethod = ClientAuthenticationMethod.BASIC;

        /**
         * Access token to send in mode 'static', as configured in the test plan
         * (ssf.transmitter.access_token).
         */
        private String accessToken;

        public Mode getMode() {
            return this.mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
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

        public String getAccessToken() {
            return this.accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public enum Mode {

            DYNAMIC, STATIC

        }

        public enum ClientAuthenticationMethod {

            BASIC, POST

        }

    }

    public static class Delivery {

        /**
         * 'push' or 'poll'. Matches the 'SSF Delivery Mode' of the test plan.
         */
        private SsfDeliveryMethod method = SsfDeliveryMethod.PUSH;

        /**
         * URL under which the conformance suite reaches the push endpoint of this
         * application. Must be https.
         */
        private URI pushUrl = URI.create("https://localhost:9443/ssf/push");

        /**
         * How often the transmitter is polled with 'poll' delivery.
         */
        private Duration pollInterval = Duration.ofSeconds(1);

        public SsfDeliveryMethod getMethod() {
            return this.method;
        }

        public void setMethod(SsfDeliveryMethod method) {
            this.method = method;
        }

        public URI getPushUrl() {
            return this.pushUrl;
        }

        public void setPushUrl(URI pushUrl) {
            this.pushUrl = pushUrl;
        }

        public Duration getPollInterval() {
            return this.pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

    }

    public static class Stream {

        /**
         * Event types (alias or URI) the stream requests.
         */
        private List<String> eventsRequested = new ArrayList<>(
                List.of("CaepSessionRevoked", "CaepCredentialChange", "CaepDeviceComplianceChange"));

        /**
         * Subject the 'remove-subject' scenario removes from the stream, e.g. one of the
         * subjects listed in the 'SSF valid SubjectId' field of the test plan.
         */
        private Map<String, Object> subjectToRemove = new LinkedHashMap<>(
                Map.of("format", "email", "email", "foo@example.com"));

        public List<String> getEventsRequested() {
            return this.eventsRequested;
        }

        public void setEventsRequested(List<String> eventsRequested) {
            this.eventsRequested = eventsRequested;
        }

        public Map<String, Object> getSubjectToRemove() {
            return this.subjectToRemove;
        }

        public void setSubjectToRemove(Map<String, Object> subjectToRemove) {
            this.subjectToRemove = subjectToRemove;
        }

    }

    public static class Run {

        /**
         * How long to wait for the verification event before going on without it.
         */
        private Duration verificationTimeout = Duration.ofSeconds(30);

        /**
         * The stream is deleted once no SET arrived for this long. Has to exceed the
         * pauses the tests make between events (20 seconds in the access token expiry
         * test, 15 seconds in the transmitter-initiated status change test).
         */
        private Duration idleTimeout = Duration.ofSeconds(30);

        /**
         * A run ends at the latest after this time.
         */
        private Duration maxDuration = Duration.ofMinutes(5);

        public Duration getVerificationTimeout() {
            return this.verificationTimeout;
        }

        public void setVerificationTimeout(Duration verificationTimeout) {
            this.verificationTimeout = verificationTimeout;
        }

        public Duration getIdleTimeout() {
            return this.idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getMaxDuration() {
            return this.maxDuration;
        }

        public void setMaxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
        }

    }

}

package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.poll.SsfPollAckStore;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.stream.SsfStreamVerification;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterUriPolicy;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.StringUtils;

/**
 * Builds the parts of a transmitter from its {@link SsfTransmitterProperties}. The
 * auto-configuration uses it for the beans of the default transmitter and for the
 * transmitters configured by name.
 */
public final class SsfTransmitterFactory {

    private static final Log logger = LogFactory.getLog(SsfTransmitterFactory.class);

    private SsfTransmitterFactory() {
    }

    /**
     * @return the prefix of the properties of the transmitter, for messages
     */
    public static String prefix(String name) {
        return SsfTransmitter.DEFAULT_NAME.equals(name) ? "easyssf.receiver."
                : "easyssf.receiver.transmitters." + name + ".";
    }

    public static String issuer(String name, SsfTransmitterProperties properties) {
        String issuer = properties.getTransmitterIssuer();
        if (!StringUtils.hasText(issuer)) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "transmitter-issuer", issuer,
                    "The issuer of the SSF transmitter must be configured");
        }
        return issuer;
    }

    public static SsfTransmitterUriPolicy uriPolicy(SsfTransmitterProperties properties) {
        return properties.isAllowInsecureHttp() ? SsfTransmitterUriPolicy.INSECURE : SsfTransmitterUriPolicy.DEFAULT;
    }

    public static SsfTransmitterMetadataResolver metadataResolver(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient) {
        String issuer = issuer(name, properties);
        SsfTransmitterMetadataResolver resolver;
        try {
            resolver = new SsfTransmitterMetadataResolver(issuer, properties.getTransmitterMetadataUrl(), httpClient,
                    uriPolicy(properties));
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "transmitter-issuer", issuer,
                    ex.getMessage());
        }
        logger.info("SSF transmitter " + describe(name, issuer) + ": metadata is resolved from "
                + resolver.getMetadataUris());
        if (properties.isAllowInsecureHttp()) {
            List<URI> insecure = Stream
                .concat(Stream.of(URI.create(issuer), properties.getTransmitterJwksUrl()),
                        resolver.getMetadataUris().stream())
                .filter(SsfTransmitterUriPolicy::isInsecure)
                .filter((uri) -> !SsfTransmitterUriPolicy.isLoopback(uri))
                .toList();
            logger.warn(prefix(name) + "allow-insecure-http is on, the SSF transmitter " + describe(name, issuer)
                    + " is used without TLS. For development only" + (insecure.isEmpty() ? "." : ": " + insecure));
        }
        return resolver;
    }

    public static NimbusSsfSetVerifier verifier(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient, SsfTransmitterMetadataResolver metadataResolver,
            SsfReceiverStream receiverStream) {
        SsfTransmitterProperties.SetValidation validation = properties.getSetValidation();
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(issuer(name, properties),
                jwkSetUri(name, properties, metadataResolver), httpClient);
        if (StringUtils.hasText(properties.getExpectedAudience())) {
            verifier.setExpectedAudience(properties.getExpectedAudience());
        }
        else if (registersStream(properties)) {
            // the transmitter tells the audience of the stream
            verifier.setExpectedAudiences(receiverStream::getAudience);
        }
        else {
            logger.warn(prefix(name) + "expected-audience is not set, SETs that the transmitter "
                    + describe(name, properties.getTransmitterIssuer()) + " issued for other receivers are accepted "
                    + "as well");
        }
        try {
            verifier.setAcceptedAlgorithms(validation.getAcceptedAlgorithms());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "set-validation.accepted-algorithms",
                    validation.getAcceptedAlgorithms(), ex.getMessage());
        }
        verifier.setMinRsaKeySize(validation.getMinRsaKeySize());
        verifier.setRequireTypeHeader(validation.isRequireTypeHeader());
        verifier.setSubjectCompatibilityMode(validation.getSubjectCompatibility());
        verifier.setClockSkew(validation.getClockSkew());
        return verifier;
    }

    public static SsfTransmitterTokenProvider tokenProvider(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient) {
        if (StringUtils.hasText(properties.getTransmitterAccessToken())) {
            String accessToken = properties.getTransmitterAccessToken();
            return () -> accessToken;
        }
        SsfTransmitterProperties.Oauth2 oauth2 = properties.getOauth2();
        if (oauth2.getTokenUri() == null) {
            return () -> null;
        }
        if (!StringUtils.hasText(oauth2.getClientId()) || !StringUtils.hasText(oauth2.getClientSecret())) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "oauth2.client-id",
                    oauth2.getClientId(),
                    prefix(name) + "oauth2.client-id and " + prefix(name)
                            + "oauth2.client-secret are required to obtain access tokens from " + prefix(name)
                            + "oauth2.token-uri");
        }
        ClientCredentialsSsfTransmitterTokenProvider tokenProvider = new ClientCredentialsSsfTransmitterTokenProvider(
                httpClient, oauth2.getTokenUri(), oauth2.getClientId(), oauth2.getClientSecret());
        tokenProvider.setScopes(oauth2.getScopes());
        tokenProvider.setAuthenticateWithRequestBody(oauth2
            .getClientAuthenticationMethod() == SsfTransmitterProperties.Oauth2.ClientAuthenticationMethod.POST);
        tokenProvider.setExpirySafetyWindow(oauth2.getExpirySafetyWindow());
        tokenProvider.setAdditionalParameters(oauth2.getAdditionalParameters());
        return tokenProvider;
    }

    public static SsfStreamClient streamClient(SsfHttpClient httpClient, SsfTransmitterTokenProvider tokenProvider,
            SsfTransmitterMetadataResolver metadataResolver) {
        return new SsfStreamClient(httpClient, tokenProvider, metadataResolver);
    }

    public static SsfStreamVerification streamVerification(SsfReceiverStream receiverStream) {
        SsfStreamVerification verification = new SsfStreamVerification();
        verification.setStreamId(receiverStream::getStreamId);
        return verification;
    }

    /**
     * @return the registrar, {@code null} if the stream is neither looked up nor managed
     */
    public static SsfStreamRegistrar streamRegistrar(String name, SsfTransmitterProperties properties,
            SsfStreamClient streamClient, SsfReceiverStream receiverStream) {
        if (!registersStream(properties)) {
            return null;
        }
        SsfTransmitterProperties.Stream stream = properties.getStream();
        if (stream.getManagement() != SsfTransmitterProperties.Stream.Management.RECEIVER) {
            return SsfStreamRegistrar.forExistingStream(streamClient, receiverStream, stream.getId());
        }
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(streamClient, receiverStream,
                desiredStream(name, properties));
        registrar.setDeleteOnShutdown(stream.isDeleteOnShutdown());
        return registrar;
    }

    /**
     * @return the poller, {@code null} with PUSH delivery
     */
    public static SsfPoller poller(String name, SsfTransmitterProperties properties, SsfHttpClient httpClient,
            SsfTransmitterTokenProvider tokenProvider, SsfSetProcessor processor, SsfReceiverStream receiverStream,
            SsfReceiverMetrics metrics) {
        return poller(name, properties, httpClient, tokenProvider, processor, receiverStream, metrics, null);
    }

    /**
     * The HTTP client the poller of a transmitter calls the transmitter with: the given
     * client, unless long polling is on and the client is not a {@link JdkSsfHttpClient}.
     * A long poll waits longer than the read timeout of the application's
     * {@code RestClient} allows, and the {@code RestClient} has no timeout per request,
     * so the poller then gets a JDK client of its own, with the connect timeout and user
     * agent of the {@code http} properties and a read timeout that covers the hold time.
     * The auto-configuration uses it; an application that builds its own poller bean
     * passes whatever client it wants to {@link #poller}.
     * @param httpClient the client of the application
     * @param http the {@code easyssf.receiver.http.*} properties, {@code null} for their
     * defaults
     */
    public static SsfHttpClient pollHttpClient(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient, SsfReceiverProperties.Http http) {
        SsfTransmitterProperties.Poll poll = properties.getPoll();
        if (properties.getDeliveryMethod() != SsfDeliveryMethod.POLL || !poll.isLongPolling()
                || httpClient instanceof JdkSsfHttpClient) {
            return httpClient;
        }
        Duration connectTimeout = (http != null && http.getConnectTimeout() != null) ? http.getConnectTimeout()
                : SsfReceiverProperties.Http.DEFAULT_TIMEOUT;
        JdkSsfHttpClient pollClient = new JdkSsfHttpClient(connectTimeout, poll.getLongPollingHold().plusSeconds(15));
        if (http != null) {
            pollClient.setUserAgent(http.getUserAgent());
        }
        logger.info(prefix(name) + "poll.long-polling is on, the poller calls the transmitter with the JDK HTTP "
                + "client rather than the RestClient of the application");
        return pollClient;
    }

    /**
     * @param httpClient the client the poller calls the transmitter with, see
     * {@link #pollHttpClient} for the one the auto-configuration chooses
     * @param ackStore keeps the acknowledgements the poller owes the transmitter,
     * {@code null} for the in-memory default
     * @return the poller, {@code null} with PUSH delivery
     */
    public static SsfPoller poller(String name, SsfTransmitterProperties properties, SsfHttpClient httpClient,
            SsfTransmitterTokenProvider tokenProvider, SsfSetProcessor processor, SsfReceiverStream receiverStream,
            SsfReceiverMetrics metrics, SsfPollAckStore ackStore) {
        if (properties.getDeliveryMethod() != SsfDeliveryMethod.POLL) {
            return null;
        }
        SsfTransmitterProperties.Poll poll = properties.getPoll();
        SsfPoller poller = new SsfPoller(httpClient, tokenProvider, pollEndpoint(name, properties, receiverStream),
                processor);
        if (ackStore != null) {
            poller.setAckStore(ackStore);
        }
        if (poll.isLongPolling()) {
            poller.setLongPolling(poll.getLongPollingHold());
        }
        poller.setTransmitter(properties.getTransmitterIssuer());
        poller.setMetrics((metrics != null) ? metrics : SsfReceiverMetrics.NOOP);
        poller.setInterval(poll.getInterval());
        poller.setInitialDelay(poll.getInitialDelay());
        poller.setMaxEvents(poll.getMaxEvents());
        poller.setRateLimitFallback(poll.getRateLimit().getFallbackBackoff());
        poller.setMaxPause(poll.getRateLimit().getMaxBackoff());
        return poller;
    }

    /**
     * A builder with every part of the transmitter built from its properties.
     */
    public static SsfTransmitter.Builder builder(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient, SsfSetProcessor processor, SsfReceiverMetrics metrics) {
        return builder(name, properties, httpClient, processor, metrics, null);
    }

    /**
     * A builder with every part of the transmitter built from its properties.
     * @param ackStore keeps the acknowledgements a poller owes the transmitter,
     * {@code null} for the in-memory default
     */
    public static SsfTransmitter.Builder builder(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient, SsfSetProcessor processor, SsfReceiverMetrics metrics, SsfPollAckStore ackStore) {
        return builder(name, properties, httpClient, processor, metrics, ackStore, null);
    }

    /**
     * A builder with every part of the transmitter built from its properties.
     * @param ackStore keeps the acknowledgements a poller owes the transmitter,
     * {@code null} for the in-memory default
     * @param http the {@code easyssf.receiver.http.*} properties, for the
     * {@link #pollHttpClient poll client} of a long polling transmitter; {@code null} for
     * their defaults
     */
    public static SsfTransmitter.Builder builder(String name, SsfTransmitterProperties properties,
            SsfHttpClient httpClient, SsfSetProcessor processor, SsfReceiverMetrics metrics, SsfPollAckStore ackStore,
            SsfReceiverProperties.Http http) {
        SsfReceiverStream receiverStream = new SsfReceiverStream();
        SsfTransmitterMetadataResolver metadataResolver = metadataResolver(name, properties, httpClient);
        SsfTransmitterTokenProvider tokenProvider = tokenProvider(name, properties, httpClient);
        SsfStreamClient streamClient = streamClient(httpClient, tokenProvider, metadataResolver);
        return SsfTransmitter.builder(name, issuer(name, properties))
            .metadataResolver(metadataResolver)
            .verifier(verifier(name, properties, httpClient, metadataResolver, receiverStream))
            .tokenProvider(tokenProvider)
            .streamClient(streamClient)
            .receiverStream(receiverStream)
            .streamVerification(streamVerification(receiverStream))
            .streamRegistrar(streamRegistrar(name, properties, streamClient, receiverStream))
            .poller(poller(name, properties, pollHttpClient(name, properties, httpClient, http), tokenProvider,
                    processor, receiverStream, metrics, ackStore), properties.getPoll().isAutoStartup())
            .pushAuthorizationHeader(properties.getPush().getExpectedAuthHeader());
    }

    public static boolean registersStream(SsfTransmitterProperties properties) {
        SsfTransmitterProperties.Stream stream = properties.getStream();
        return stream.getManagement() == SsfTransmitterProperties.Stream.Management.RECEIVER
                || StringUtils.hasText(stream.getId());
    }

    private static SsfStreamConfiguration desiredStream(String name, SsfTransmitterProperties properties) {
        SsfTransmitterProperties.Stream stream = properties.getStream();
        if (stream.getEventsRequested().isEmpty()) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "stream.events-requested",
                    stream.getEventsRequested(), "A stream managed by the receiver has to request at least one event");
        }
        if (properties.getDeliveryMethod() == SsfDeliveryMethod.POLL) {
            return SsfStreamConfiguration.poll(stream.getEventsRequested(), stream.getDescription());
        }
        URI deliveryEndpointUrl = properties.getPush().getDeliveryEndpointUrl();
        if (deliveryEndpointUrl == null) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "push.delivery-endpoint-url", null,
                    "A stream managed by the receiver needs the URL under which the transmitter reaches the "
                            + "push endpoint of this application");
        }
        String authorizationHeader = properties.getPush().getExpectedAuthHeader();
        return SsfStreamConfiguration.push(deliveryEndpointUrl,
                StringUtils.hasText(authorizationHeader) ? authorizationHeader : null, stream.getEventsRequested(),
                stream.getDescription());
    }

    private static Supplier<URI> pollEndpoint(String name, SsfTransmitterProperties properties,
            SsfReceiverStream receiverStream) {
        URI configured = properties.getPoll().getEndpointUrl();
        if (configured != null) {
            return () -> configured;
        }
        if (!registersStream(properties)) {
            throw new InvalidConfigurationPropertyValueException(prefix(name) + "poll.endpoint-url", null,
                    "POLL delivery needs the poll endpoint of the stream: set " + prefix(name) + "poll.endpoint-url, "
                            + prefix(name) + "stream.id or " + prefix(name) + "stream.management=receiver");
        }
        return () -> receiverStream.getConfiguration()
            .filter((stream) -> SsfDeliveryMethod.POLL.uri().equals(stream.deliveryMethod()))
            .map(SsfStreamConfiguration::deliveryEndpointUrl)
            .orElse(null);
    }

    private static Supplier<String> jwkSetUri(String name, SsfTransmitterProperties properties,
            SsfTransmitterMetadataResolver metadataResolver) {
        URI configured = properties.getTransmitterJwksUrl();
        if (configured != null) {
            try {
                uriPolicy(properties).checkEndpoint(configured, "The JWK Set URL");
            }
            catch (IllegalArgumentException ex) {
                throw new InvalidConfigurationPropertyValueException(prefix(name) + "transmitter-jwks-url", configured,
                        ex.getMessage());
            }
            return configured::toString;
        }
        return () -> {
            URI discovered = metadataResolver.resolve().jwksUri();
            if (discovered == null) {
                throw new IllegalStateException("The SSF transmitter metadata has no jwks_uri, configure "
                        + prefix(name) + "transmitter-jwks-url");
            }
            return discovered.toString();
        };
    }

    private static String describe(String name, String issuer) {
        return SsfTransmitter.DEFAULT_NAME.equals(name) ? issuer : "'" + name + "' (" + issuer + ")";
    }

}

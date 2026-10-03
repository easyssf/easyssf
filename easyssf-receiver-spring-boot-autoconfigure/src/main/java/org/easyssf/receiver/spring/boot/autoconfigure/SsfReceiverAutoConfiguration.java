package org.easyssf.receiver.spring.boot.autoconfigure;

import java.net.URI;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.spring.boot.SsfReceiverLifecycle;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.stream.SsfStreamVerification;
import org.easyssf.receiver.transmitter.ClientCredentialsSsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

/**
 * {@link AutoConfiguration Auto-configuration} for the parts of the SSF receiver that do
 * not depend on the web stack: verification, de-duplication and dispatching of SETs,
 * stream management and POLL delivery.
 *
 * <p>
 * Like all auto-configurations of the receiver it has the lowest precedence: they are
 * sorted behind the auto-configurations of Spring Boot before {@code after} and
 * {@code before} are applied. Otherwise naming an auto-configuration of Spring Boot in
 * {@code afterName} would move it ahead of others it has to come after.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass(SsfSetProcessor.class)
@ConditionalOnBooleanProperty(name = "easyssf.receiver.enabled", matchIfMissing = true)
@EnableConfigurationProperties(SsfReceiverProperties.class)
public final class SsfReceiverAutoConfiguration {

    private static final Log logger = LogFactory.getLog(SsfReceiverAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SsfHttpClient.class)
    JdkSsfHttpClient ssfHttpClient(SsfReceiverProperties properties) {
        SsfReceiverProperties.Http http = properties.getHttp();
        JdkSsfHttpClient httpClient = new JdkSsfHttpClient(
                (http.getConnectTimeout() != null) ? http.getConnectTimeout()
                        : SsfReceiverProperties.Http.DEFAULT_TIMEOUT,
                (http.getReadTimeout() != null) ? http.getReadTimeout() : SsfReceiverProperties.Http.DEFAULT_TIMEOUT);
        httpClient.setUserAgent(http.getUserAgent());
        return httpClient;
    }

    @Bean
    @ConditionalOnMissingBean
    SsfTransmitterMetadataResolver ssfTransmitterMetadataResolver(SsfReceiverProperties properties,
            SsfHttpClient httpClient) {
        SsfTransmitterMetadataResolver resolver = new SsfTransmitterMetadataResolver(transmitterIssuer(properties),
                properties.getTransmitterMetadataUrl(), httpClient);
        logger.info("SSF transmitter metadata is resolved from " + resolver.getMetadataUris());
        return resolver;
    }

    @Bean
    @ConditionalOnMissingBean(SsfSetVerifier.class)
    NimbusSsfSetVerifier ssfSetVerifier(SsfReceiverProperties properties, SsfHttpClient httpClient,
            SsfTransmitterMetadataResolver metadataResolver, SsfReceiverStream receiverStream) {
        SsfReceiverProperties.SetValidation validation = properties.getSetValidation();
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitterIssuer(properties),
                jwkSetUri(properties, metadataResolver), httpClient);
        if (StringUtils.hasText(properties.getExpectedAudience())) {
            verifier.setExpectedAudience(properties.getExpectedAudience());
        }
        else if (registersStream(properties)) {
            // the transmitter tells the audience of the stream
            verifier.setExpectedAudiences(receiverStream::getAudience);
        }
        else {
            logger.warn("easyssf.receiver.expected-audience is not set, SETs that the transmitter issued "
                    + "for other receivers are accepted as well");
        }
        try {
            verifier.setAcceptedAlgorithms(validation.getAcceptedAlgorithms());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.set-validation.accepted-algorithms",
                    validation.getAcceptedAlgorithms(), ex.getMessage());
        }
        verifier.setMinRsaKeySize(validation.getMinRsaKeySize());
        verifier.setRequireTypeHeader(validation.isRequireTypeHeader());
        verifier.setClockSkew(validation.getClockSkew());
        return verifier;
    }

    @Bean
    @ConditionalOnMissingBean(SsfJtiDedupStore.class)
    @ConditionalOnBooleanProperty(name = "easyssf.receiver.dedup.enabled", matchIfMissing = true)
    InMemorySsfJtiDedupStore ssfJtiDedupStore(SsfReceiverProperties properties) {
        return new InMemorySsfJtiDedupStore(properties.getDedup().getCapacity());
    }

    @Bean
    @ConditionalOnMissingBean
    SsfStreamVerification ssfStreamVerification(SsfReceiverStream receiverStream) {
        SsfStreamVerification verification = new SsfStreamVerification();
        verification.setStreamId(receiverStream::getStreamId);
        return verification;
    }

    @Bean
    @ConditionalOnMissingBean
    SsfSetProcessor ssfSetProcessor(SsfReceiverProperties properties, SsfSetVerifier verifier,
            ObjectProvider<SsfJtiDedupStore> dedupStore, ObjectProvider<SsfEventHandler> handlers,
            ObjectProvider<SsfReceiverMetrics> metrics, SsfStreamVerification streamVerification) {
        SsfJtiDedupStore store = properties.getDedup().isEnabled() ? dedupStore.getIfAvailable() : null;
        SsfSetProcessor processor = new SsfSetProcessor(verifier, store, handlers.orderedStream().toList());
        processor.setMetrics(metrics.getIfAvailable(() -> SsfReceiverMetrics.NOOP));
        processor.setStreamVerification(streamVerification);
        return processor;
    }

    @Bean
    @ConditionalOnMissingBean
    SsfTransmitterTokenProvider ssfTransmitterTokenProvider(SsfReceiverProperties properties,
            SsfHttpClient httpClient) {
        if (StringUtils.hasText(properties.getTransmitterAccessToken())) {
            String accessToken = properties.getTransmitterAccessToken();
            return () -> accessToken;
        }
        SsfReceiverProperties.Oauth2 oauth2 = properties.getOauth2();
        if (oauth2.getTokenUri() == null) {
            return () -> null;
        }
        if (!StringUtils.hasText(oauth2.getClientId()) || !StringUtils.hasText(oauth2.getClientSecret())) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.oauth2.client-id",
                    oauth2.getClientId(), "easyssf.receiver.oauth2.client-id and easyssf.receiver.oauth2.client-secret "
                            + "are required to obtain access tokens from easyssf.receiver.oauth2.token-uri");
        }
        ClientCredentialsSsfTransmitterTokenProvider tokenProvider = new ClientCredentialsSsfTransmitterTokenProvider(
                httpClient, oauth2.getTokenUri(), oauth2.getClientId(), oauth2.getClientSecret());
        tokenProvider.setScopes(oauth2.getScopes());
        tokenProvider.setAuthenticateWithRequestBody(
                oauth2.getClientAuthenticationMethod() == SsfReceiverProperties.Oauth2.ClientAuthenticationMethod.POST);
        return tokenProvider;
    }

    @Bean
    @ConditionalOnMissingBean
    SsfStreamClient ssfStreamClient(SsfHttpClient httpClient, SsfTransmitterTokenProvider tokenProvider,
            SsfTransmitterMetadataResolver metadataResolver) {
        return new SsfStreamClient(httpClient, tokenProvider, metadataResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    SsfReceiverStream ssfReceiverStream() {
        return new SsfReceiverStream();
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(StreamRegistrationCondition.class)
    SsfStreamRegistrar ssfStreamRegistrar(SsfReceiverProperties properties, SsfStreamClient streamClient,
            SsfReceiverStream receiverStream) {
        SsfReceiverProperties.Stream stream = properties.getStream();
        if (stream.getManagement() != SsfReceiverProperties.Stream.Management.RECEIVER) {
            return SsfStreamRegistrar.forExistingStream(streamClient, receiverStream, stream.getId());
        }
        SsfStreamRegistrar registrar = SsfStreamRegistrar.forManagedStream(streamClient, receiverStream,
                desiredStream(properties));
        registrar.setDeleteOnShutdown(stream.isDeleteOnShutdown());
        return registrar;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "easyssf.receiver.delivery-method", havingValue = "poll")
    SsfPoller ssfPoller(SsfReceiverProperties properties, SsfHttpClient httpClient,
            SsfTransmitterTokenProvider tokenProvider, SsfSetProcessor processor, SsfReceiverStream receiverStream,
            ObjectProvider<SsfReceiverMetrics> metrics) {
        SsfReceiverProperties.Poll poll = properties.getPoll();
        SsfPoller poller = new SsfPoller(httpClient, tokenProvider, pollEndpoint(properties, receiverStream),
                processor);
        poller.setMetrics(metrics.getIfAvailable(() -> SsfReceiverMetrics.NOOP));
        poller.setInterval(poll.getInterval());
        poller.setInitialDelay(poll.getInitialDelay());
        poller.setMaxEvents(poll.getMaxEvents());
        return poller;
    }

    /**
     * Starts and stops the stream registration and the periodic polling with the
     * application context.
     */
    @Bean
    @ConditionalOnMissingBean
    SsfReceiverLifecycle ssfReceiverLifecycle(SsfReceiverProperties properties,
            ObjectProvider<SsfStreamRegistrar> streamRegistrar, ObjectProvider<SsfPoller> poller) {
        return new SsfReceiverLifecycle(streamRegistrar.getIfAvailable(),
                properties.getPoll().isAutoStartup() ? poller.getIfAvailable() : null);
    }

    private static SsfStreamConfiguration desiredStream(SsfReceiverProperties properties) {
        SsfReceiverProperties.Stream stream = properties.getStream();
        if (stream.getEventsRequested().isEmpty()) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.stream.events-requested",
                    stream.getEventsRequested(), "A stream managed by the receiver has to request at least one event");
        }
        if (properties.getDeliveryMethod() == SsfDeliveryMethod.POLL) {
            return SsfStreamConfiguration.poll(stream.getEventsRequested(), stream.getDescription());
        }
        URI deliveryEndpointUrl = properties.getPush().getDeliveryEndpointUrl();
        if (deliveryEndpointUrl == null) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.push.delivery-endpoint-url", null,
                    "A stream managed by the receiver needs the URL under which the transmitter reaches the "
                            + "push endpoint of this application");
        }
        String authorizationHeader = properties.getPush().getExpectedAuthHeader();
        return SsfStreamConfiguration.push(deliveryEndpointUrl,
                StringUtils.hasText(authorizationHeader) ? authorizationHeader : null, stream.getEventsRequested(),
                stream.getDescription());
    }

    private static Supplier<URI> pollEndpoint(SsfReceiverProperties properties, SsfReceiverStream receiverStream) {
        URI configured = properties.getPoll().getEndpointUrl();
        if (configured != null) {
            return () -> configured;
        }
        if (!registersStream(properties)) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.poll.endpoint-url", null,
                    "POLL delivery needs the poll endpoint of the stream: set easyssf.receiver.poll.endpoint-url, "
                            + "easyssf.receiver.stream.id or easyssf.receiver.stream.management=receiver");
        }
        return () -> receiverStream.getConfiguration()
            .filter((stream) -> SsfDeliveryMethod.POLL.uri().equals(stream.deliveryMethod()))
            .map(SsfStreamConfiguration::deliveryEndpointUrl)
            .orElse(null);
    }

    private static boolean registersStream(SsfReceiverProperties properties) {
        SsfReceiverProperties.Stream stream = properties.getStream();
        return stream.getManagement() == SsfReceiverProperties.Stream.Management.RECEIVER
                || StringUtils.hasText(stream.getId());
    }

    private static String transmitterIssuer(SsfReceiverProperties properties) {
        String issuer = properties.getTransmitterIssuer();
        if (!StringUtils.hasText(issuer)) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.transmitter-issuer", issuer,
                    "The issuer of the SSF transmitter must be configured. "
                            + "Set easyssf.receiver.enabled=false to switch the receiver off.");
        }
        return issuer;
    }

    private static Supplier<String> jwkSetUri(SsfReceiverProperties properties,
            SsfTransmitterMetadataResolver metadataResolver) {
        URI configured = properties.getTransmitterJwksUrl();
        if (configured != null) {
            return configured::toString;
        }
        return () -> {
            URI discovered = metadataResolver.resolve().jwksUri();
            if (discovered == null) {
                throw new IllegalStateException("The SSF transmitter metadata has no jwks_uri, "
                        + "configure easyssf.receiver.transmitter-jwks-url");
            }
            return discovered.toString();
        };
    }

    /**
     * Matches when the stream is looked up or managed on startup.
     */
    static class StreamRegistrationCondition extends AnyNestedCondition {

        StreamRegistrationCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "easyssf.receiver.stream.management", havingValue = "receiver")
        static class ManagedByReceiver {

        }

        @ConditionalOnProperty(name = "easyssf.receiver.stream.id")
        static class StreamIdConfigured {

        }

    }

}

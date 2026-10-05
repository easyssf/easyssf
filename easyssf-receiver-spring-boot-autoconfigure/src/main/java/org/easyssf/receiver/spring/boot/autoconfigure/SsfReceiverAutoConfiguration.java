package org.easyssf.receiver.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.poll.SsfPollAckStore;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.spring.boot.SsfReceiverLifecycle;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.SsfTransmitterCustomizer;
import org.easyssf.receiver.spring.boot.SsfTransmitterFactory;
import org.easyssf.receiver.spring.boot.SsfTransmitterProperties;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.stream.SsfStreamVerification;
import org.easyssf.receiver.transmitter.SsfTransmitter;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitters;
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

/**
 * {@link AutoConfiguration Auto-configuration} for the parts of the SSF receiver that do
 * not depend on the web stack: verification, de-duplication and dispatching of SETs,
 * stream management and POLL delivery.
 *
 * <p>
 * The parts of the {@link SsfTransmitter#DEFAULT_NAME default} transmitter, configured at
 * {@code easyssf.receiver.*}, are beans, so each of them can be replaced by a bean of its
 * type. The transmitters configured at {@code easyssf.receiver.transmitters.<name>.*} are
 * built by the {@link SsfTransmitterFactory} and customized with
 * {@link SsfTransmitterCustomizer} beans. All of them are available from the
 * {@link SsfTransmitters} bean.
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

    private static final String DEFAULT_ISSUER_PROPERTY = "easyssf.receiver.transmitter-issuer";

    private static final Log logger = LogFactory.getLog(SsfReceiverAutoConfiguration.class);

    /**
     * Registers the configured event type aliases before any bean resolves event types.
     */
    SsfReceiverAutoConfiguration(SsfReceiverProperties properties) {
        properties.getEventAliases().forEach((alias, uri) -> {
            try {
                SsfEventTypes.registerAlias(alias, uri);
            }
            catch (IllegalArgumentException ex) {
                throw new InvalidConfigurationPropertyValueException("easyssf.receiver.event-aliases." + alias, uri,
                        ex.getMessage());
            }
        });
    }

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

    // ---- the default transmitter, configured at easyssf.receiver.*

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    SsfTransmitterMetadataResolver ssfTransmitterMetadataResolver(SsfReceiverProperties properties,
            SsfHttpClient httpClient) {
        return SsfTransmitterFactory.metadataResolver(SsfTransmitter.DEFAULT_NAME, properties, httpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    SsfReceiverStream ssfReceiverStream() {
        return new SsfReceiverStream();
    }

    @Bean
    @ConditionalOnMissingBean(SsfSetVerifier.class)
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    NimbusSsfSetVerifier ssfSetVerifier(SsfReceiverProperties properties, SsfHttpClient httpClient,
            SsfTransmitterMetadataResolver metadataResolver, SsfReceiverStream receiverStream) {
        return SsfTransmitterFactory.verifier(SsfTransmitter.DEFAULT_NAME, properties, httpClient, metadataResolver,
                receiverStream);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    SsfStreamVerification ssfStreamVerification(SsfReceiverStream receiverStream) {
        return SsfTransmitterFactory.streamVerification(receiverStream);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    SsfTransmitterTokenProvider ssfTransmitterTokenProvider(SsfReceiverProperties properties,
            SsfHttpClient httpClient) {
        return SsfTransmitterFactory.tokenProvider(SsfTransmitter.DEFAULT_NAME, properties, httpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    SsfStreamClient ssfStreamClient(SsfHttpClient httpClient, SsfTransmitterTokenProvider tokenProvider,
            SsfTransmitterMetadataResolver metadataResolver) {
        return SsfTransmitterFactory.streamClient(httpClient, tokenProvider, metadataResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    @Conditional(StreamRegistrationCondition.class)
    SsfStreamRegistrar ssfStreamRegistrar(SsfReceiverProperties properties, SsfStreamClient streamClient,
            SsfReceiverStream receiverStream) {
        return SsfTransmitterFactory.streamRegistrar(SsfTransmitter.DEFAULT_NAME, properties, streamClient,
                receiverStream);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(DEFAULT_ISSUER_PROPERTY)
    @ConditionalOnProperty(name = "easyssf.receiver.delivery-method", havingValue = "poll")
    SsfPoller ssfPoller(SsfReceiverProperties properties, SsfHttpClient httpClient,
            SsfTransmitterTokenProvider tokenProvider, SsfSetProcessor processor, SsfReceiverStream receiverStream,
            ObjectProvider<SsfReceiverMetrics> metrics, ObjectProvider<SsfPollAckStore> ackStore) {
        SsfHttpClient pollClient = SsfTransmitterFactory.pollHttpClient(SsfTransmitter.DEFAULT_NAME, properties,
                httpClient, properties.getHttp());
        return SsfTransmitterFactory.poller(SsfTransmitter.DEFAULT_NAME, properties, pollClient, tokenProvider,
                processor, receiverStream, metrics.getIfAvailable(), ackStore.getIfAvailable());
    }

    // ---- shared by all transmitters

    @Bean
    @ConditionalOnMissingBean(SsfJtiDedupStore.class)
    @ConditionalOnBooleanProperty(name = "easyssf.receiver.dedup.enabled", matchIfMissing = true)
    InMemorySsfJtiDedupStore ssfJtiDedupStore(SsfReceiverProperties properties) {
        InMemorySsfJtiDedupStore store = new InMemorySsfJtiDedupStore(properties.getDedup().getCapacity());
        store.setLease(properties.getDedup().getLease());
        return store;
    }

    /**
     * Processes the SETs of every transmitter: the issuer of a SET selects the
     * transmitter that verifies it. The transmitters are looked up when the first SET
     * arrives, as the pollers of the transmitters need the processor.
     */
    @Bean
    @ConditionalOnMissingBean
    SsfSetProcessor ssfSetProcessor(SsfReceiverProperties properties, ObjectProvider<SsfTransmitters> transmitters,
            ObjectProvider<SsfJtiDedupStore> dedupStore, ObjectProvider<SsfEventHandler> handlers,
            ObjectProvider<SsfReceiverMetrics> metrics) {
        SsfJtiDedupStore store = properties.getDedup().isEnabled() ? dedupStore.getIfAvailable() : null;
        SsfSetVerifier verifier = (encodedSet) -> transmitters.getObject().verifier().verify(encodedSet);
        SsfSetProcessor processor = new SsfSetProcessor(verifier, store, handlers.orderedStream().toList());
        processor.setMetrics(metrics.getIfAvailable(() -> SsfReceiverMetrics.NOOP));
        processor.setStreamVerifications((issuer) -> transmitters.getObject().streamVerification(issuer));
        processor.setUnderstoodSubjectMembers(Set.copyOf(properties.getUnderstoodSubjectMembers()));
        processor.setCriticalSubjectMembers((issuer) -> transmitters.getObject().criticalSubjectMembers(issuer));
        return processor;
    }

    /**
     * The transmitters of the receiver: the default one assembled from its beans, the
     * named ones built from their properties.
     */
    @Bean
    @ConditionalOnMissingBean
    SsfTransmitters ssfTransmitters(SsfReceiverProperties properties, SsfHttpClient httpClient,
            SsfSetProcessor processor, ObjectProvider<SsfReceiverMetrics> metrics,
            ObjectProvider<SsfTransmitterMetadataResolver> metadataResolver, ObjectProvider<SsfSetVerifier> verifier,
            ObjectProvider<SsfTransmitterTokenProvider> tokenProvider, ObjectProvider<SsfStreamClient> streamClient,
            ObjectProvider<SsfReceiverStream> receiverStream, ObjectProvider<SsfStreamVerification> streamVerification,
            ObjectProvider<SsfStreamRegistrar> streamRegistrar, ObjectProvider<SsfPoller> poller,
            ObjectProvider<SsfTransmitterCustomizer> customizers, ObjectProvider<SsfPollAckStore> ackStore) {
        Map<String, SsfTransmitterProperties> configured;
        try {
            configured = properties.getConfiguredTransmitters();
        }
        catch (IllegalStateException ex) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.transmitters", null,
                    ex.getMessage());
        }
        if (configured.isEmpty()) {
            throw new InvalidConfigurationPropertyValueException(DEFAULT_ISSUER_PROPERTY, null,
                    "The issuer of the SSF transmitter must be configured, or transmitters under "
                            + "easyssf.receiver.transmitters. Set easyssf.receiver.enabled=false to switch the "
                            + "receiver off.");
        }
        List<SsfTransmitter> transmitters = new ArrayList<>();
        configured.forEach((name, transmitterProperties) -> {
            SsfTransmitter.Builder builder;
            if (SsfTransmitter.DEFAULT_NAME.equals(name) && transmitterProperties == properties) {
                builder = SsfTransmitter.builder(name, SsfTransmitterFactory.issuer(name, properties))
                    .metadataResolver(metadataResolver.getObject())
                    .verifier(verifier.getObject())
                    .tokenProvider(tokenProvider.getObject())
                    .streamClient(streamClient.getObject())
                    .receiverStream(receiverStream.getObject())
                    .streamVerification(streamVerification.getObject())
                    .streamRegistrar(streamRegistrar.getIfAvailable())
                    .poller(poller.getIfAvailable(), properties.getPoll().isAutoStartup())
                    .pushAuthorizationHeader(properties.getPush().getExpectedAuthHeader());
            }
            else {
                builder = SsfTransmitterFactory.builder(name, transmitterProperties, httpClient, processor,
                        metrics.getIfAvailable(), ackStore.getIfAvailable(), properties.getHttp());
            }
            customizers.orderedStream().forEach((customizer) -> customizer.customize(builder));
            transmitters.add(builder.build());
        });
        try {
            SsfTransmitters all = new SsfTransmitters(transmitters);
            if (all.all().size() > 1) {
                logger.info("SSF transmitters: " + all.all());
            }
            return all;
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.transmitters", null,
                    ex.getMessage());
        }
    }

    /**
     * Starts and stops the stream registration and the periodic polling of every
     * transmitter with the application context.
     */
    @Bean
    @ConditionalOnMissingBean
    SsfReceiverLifecycle ssfReceiverLifecycle(SsfTransmitters transmitters) {
        return new SsfReceiverLifecycle(transmitters);
    }

    /**
     * Matches when the stream of the default transmitter is looked up or managed on
     * startup.
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

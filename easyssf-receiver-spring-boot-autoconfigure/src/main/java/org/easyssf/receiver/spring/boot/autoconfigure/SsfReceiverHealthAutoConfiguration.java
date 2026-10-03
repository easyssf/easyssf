package org.easyssf.receiver.spring.boot.autoconfigure;

import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.health.SsfReceiverHealthIndicator;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * {@link AutoConfiguration Auto-configuration} of the {@code easyssf} health indicator,
 * if the application has Spring Boot's health support (Actuator).
 */
@AutoConfiguration(after = SsfReceiverAutoConfiguration.class)
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(SsfTransmitterMetadataResolver.class)
@ConditionalOnEnabledHealthIndicator("easyssf")
public final class SsfReceiverHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "easyssfHealthIndicator")
    SsfReceiverHealthIndicator easyssfHealthIndicator(SsfReceiverProperties properties,
            SsfTransmitterMetadataResolver metadataResolver, SsfReceiverStream receiverStream,
            ObjectProvider<SsfStreamRegistrar> streamRegistrar, ObjectProvider<SsfPoller> poller) {
        return new SsfReceiverHealthIndicator(properties.getTransmitterIssuer(), metadataResolver, receiverStream,
                streamRegistrar.getIfAvailable(), poller.getIfAvailable());
    }

}

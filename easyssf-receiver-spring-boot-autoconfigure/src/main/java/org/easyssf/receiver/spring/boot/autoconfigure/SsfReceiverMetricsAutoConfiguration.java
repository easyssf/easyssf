package org.easyssf.receiver.spring.boot.autoconfigure;

import org.easyssf.receiver.metrics.MicrometerSsfReceiverMetrics;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link AutoConfiguration Auto-configuration} that records what the SSF receiver does
 * with Micrometer, if the application has a {@link MeterRegistry}.
 */
@AutoConfiguration(before = SsfReceiverAutoConfiguration.class, afterName = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration" })
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnBooleanProperty(name = "easyssf.receiver.enabled", matchIfMissing = true)
public final class SsfReceiverMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SsfReceiverMetrics.class)
    @ConditionalOnBooleanProperty(name = "easyssf.receiver.metrics.enabled", matchIfMissing = true)
    MicrometerSsfReceiverMetrics ssfReceiverMetrics(MeterRegistry meterRegistry,
            ObjectProvider<SsfTransmitters> transmitters) {
        MicrometerSsfReceiverMetrics metrics = new MicrometerSsfReceiverMetrics(meterRegistry);
        // the transmitters are built after the metrics, so they are looked up per meter
        metrics.setTransmitterLabels((issuer) -> {
            SsfTransmitters registry = transmitters.getIfAvailable();
            return (registry != null) ? registry.nameOf(issuer) : issuer;
        });
        return metrics;
    }

}

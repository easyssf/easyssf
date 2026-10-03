package org.easyssf.receiver.spring.boot.autoconfigure;

import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.SsfTransmitterProperties;
import org.easyssf.receiver.spring.boot.web.SsfPushEndpoint;
import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * {@link AutoConfiguration Auto-configuration} for the endpoint that receives SETs
 * delivered using HTTP push (RFC 8935) in a Spring MVC application.
 */
@AutoConfiguration(after = SsfReceiverAutoConfiguration.class)
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ DispatcherServlet.class, RouterFunction.class })
@ConditionalOnBean({ SsfSetProcessor.class, SsfTransmitters.class })
@ConditionalOnBooleanProperty(name = "easyssf.receiver.push.enabled", matchIfMissing = true)
@Conditional(SsfReceiverPushAutoConfiguration.AnyTransmitterPushesCondition.class)
public final class SsfReceiverPushAutoConfiguration {

    private static final Log logger = LogFactory.getLog(SsfReceiverPushAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    SsfPushHandler ssfPushHandler(SsfSetProcessor processor, SsfTransmitters transmitters,
            ObjectProvider<SsfReceiverMetrics> metrics) {
        // with one transmitter the header is checked before the SET is even parsed
        SsfPushHandler pushHandler = (transmitters.all().size() == 1)
                ? new SsfPushHandler(processor, transmitters.all().get(0).getPushAuthorizationHeader())
                : new SsfPushHandler(processor, transmitters::pushAuthorizationHeader);
        pushHandler.setMetrics(metrics.getIfAvailable(() -> SsfReceiverMetrics.NOOP));
        return pushHandler;
    }

    @Bean
    @ConditionalOnMissingBean
    SsfPushEndpoint ssfPushEndpoint(SsfPushHandler pushHandler) {
        return new SsfPushEndpoint(pushHandler);
    }

    @Bean
    RouterFunction<ServerResponse> ssfPushRouterFunction(SsfPushEndpoint endpoint, SsfReceiverProperties properties) {
        String path = pushEndpointPath(properties);
        logger.info("Receiving SSF security events at " + path);
        return RouterFunctions.route()
            .POST(path, endpoint::handle)
            .route(RequestPredicates.path(path),
                    (request) -> ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.POST).build())
            .build();
    }

    /**
     * Matches when a transmitter delivers by PUSH, or none is configured (the error for
     * that is reported elsewhere).
     */
    static class AnyTransmitterPushesCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            SsfReceiverProperties properties = Binder.get(context.getEnvironment())
                .bind("easyssf.receiver", SsfReceiverProperties.class)
                .orElseGet(SsfReceiverProperties::new);
            Map<String, SsfTransmitterProperties> transmitters;
            try {
                transmitters = properties.getConfiguredTransmitters();
            }
            catch (IllegalStateException ex) {
                return ConditionOutcome.match("the transmitters are not configured consistently");
            }
            if (transmitters.isEmpty()) {
                return ConditionOutcome.match("no transmitter is configured");
            }
            List<String> pushing = transmitters.entrySet()
                .stream()
                .filter((entry) -> entry.getValue().getDeliveryMethod() == SsfDeliveryMethod.PUSH)
                .map(Map.Entry::getKey)
                .toList();
            return pushing.isEmpty() ? ConditionOutcome.noMatch("no transmitter delivers by push")
                    : ConditionOutcome.match("push delivery from " + pushing);
        }

    }

    static String pushEndpointPath(SsfReceiverProperties properties) {
        String path = properties.getPush().getEndpointPath();
        if (!StringUtils.hasText(path) || !path.startsWith("/")) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.push.endpoint-path", path,
                    "The path of the push endpoint must start with '/'");
        }
        return path;
    }

}

package org.easyssf.receiver.spring.boot.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.web.SsfPushEndpoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
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
@ConditionalOnBean(SsfSetProcessor.class)
@ConditionalOnBooleanProperty(name = "easyssf.receiver.push.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "easyssf.receiver.delivery-method", havingValue = "push", matchIfMissing = true)
public final class SsfReceiverPushAutoConfiguration {

    private static final Log logger = LogFactory.getLog(SsfReceiverPushAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    SsfPushHandler ssfPushHandler(SsfSetProcessor processor, SsfReceiverProperties properties,
            ObjectProvider<SsfReceiverMetrics> metrics) {
        String expectedAuthHeader = properties.getPush().getExpectedAuthHeader();
        SsfPushHandler pushHandler = new SsfPushHandler(processor,
                StringUtils.hasText(expectedAuthHeader) ? expectedAuthHeader : null);
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

    static String pushEndpointPath(SsfReceiverProperties properties) {
        String path = properties.getPush().getEndpointPath();
        if (!StringUtils.hasText(path) || !path.startsWith("/")) {
            throw new InvalidConfigurationPropertyValueException("easyssf.receiver.push.endpoint-path", path,
                    "The path of the push endpoint must start with '/'");
        }
        return path;
    }

}

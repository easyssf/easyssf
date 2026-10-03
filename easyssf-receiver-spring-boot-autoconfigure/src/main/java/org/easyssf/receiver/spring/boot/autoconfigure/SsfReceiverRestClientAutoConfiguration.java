package org.easyssf.receiver.spring.boot.autoconfigure;

import java.time.Duration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.http.RestClientSsfHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * {@link AutoConfiguration Auto-configuration} that makes the receiver call the
 * transmitter with the {@link RestClient} of the application, if it has a
 * {@code RestClient.Builder}: the calls then use the HTTP client library, TLS
 * configuration and customizers of the application and are observed like its other HTTP
 * calls.
 *
 * <p>
 * It is processed before the auto-configuration that falls back to the HTTP client of the
 * JDK.
 */
@AutoConfiguration(before = SsfReceiverAutoConfiguration.class,
        afterName = { "org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration",
                "org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration",
                "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration",
                "org.springframework.boot.restclient.autoconfigure.RestClientObservationAutoConfiguration" })
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass({ RestClient.class, HttpClientSettings.class, ClientHttpRequestFactoryBuilder.class })
@ConditionalOnBean(RestClient.Builder.class)
@ConditionalOnBooleanProperty(name = { "easyssf.receiver.enabled", "easyssf.receiver.http.use-rest-client" },
        matchIfMissing = true)
@EnableConfigurationProperties(SsfReceiverProperties.class)
public final class SsfReceiverRestClientAutoConfiguration {

    private static final Log logger = LogFactory.getLog(SsfReceiverRestClientAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SsfHttpClient.class)
    RestClientSsfHttpClient ssfRestClientHttpClient(RestClient.Builder restClientBuilder,
            ObjectProvider<ClientHttpRequestFactoryBuilder<?>> requestFactoryBuilder,
            ObjectProvider<HttpClientSettings> httpClientSettings, SsfReceiverProperties properties) {
        SsfReceiverProperties.Http http = properties.getHttp();
        // the builder may be shared with the application
        RestClient.Builder builder = restClientBuilder.clone();
        ClientHttpRequestFactoryBuilder<?> factoryBuilder = requestFactoryBuilder.getIfAvailable();
        if (factoryBuilder != null) {
            HttpClientSettings settings = httpClientSettings.getIfAvailable(HttpClientSettings::defaults);
            Duration connectTimeout = timeout(http.getConnectTimeout(), settings.connectTimeout());
            Duration readTimeout = timeout(http.getReadTimeout(), settings.readTimeout());
            builder.requestFactory(factoryBuilder.build(settings.withTimeouts(connectTimeout, readTimeout)));
        }
        if (StringUtils.hasText(http.getUserAgent())) {
            builder.defaultHeader(HttpHeaders.USER_AGENT, http.getUserAgent());
        }
        logger.info("The SSF transmitter is called with the RestClient of the application");
        return new RestClientSsfHttpClient(builder.build());
    }

    /**
     * The timeout configured for the receiver, else the one configured for the HTTP
     * clients of the application, else a default: calls to the transmitter never wait
     * forever.
     */
    private static Duration timeout(Duration configured, Duration applicationDefault) {
        if (configured != null) {
            return configured;
        }
        return (applicationDefault != null) ? applicationDefault : SsfReceiverProperties.Http.DEFAULT_TIMEOUT;
    }

}

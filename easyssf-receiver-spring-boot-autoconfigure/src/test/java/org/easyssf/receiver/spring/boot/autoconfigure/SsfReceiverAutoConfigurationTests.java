package org.easyssf.receiver.spring.boot.autoconfigure;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.metrics.MicrometerSsfReceiverMetrics;
import org.easyssf.receiver.metrics.SsfReceiverMetrics;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.revocation.InMemorySsfTokenRevocationStore;
import org.easyssf.receiver.revocation.SsfTokenRevocationEventHandler;
import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.easyssf.receiver.session.SsfSessionTerminationEventHandler;
import org.easyssf.receiver.session.SsfSessionTerminator;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.spring.boot.http.RestClientSsfHttpClient;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfJtiDedupStore;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfStoreCleanup;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfTokenRevocationStore;
import org.easyssf.receiver.spring.boot.resourceserver.SsfRevokedTokenValidator;
import org.easyssf.receiver.spring.boot.web.SsfPushEndpoint;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SsfReceiverAutoConfigurationTests {

    private static final AutoConfigurations SSF_RECEIVER = AutoConfigurations.of(SsfReceiverAutoConfiguration.class,
            SsfReceiverMetricsAutoConfiguration.class, SsfReceiverJdbcAutoConfiguration.class,
            SsfReceiverRestClientAutoConfiguration.class, SsfReceiverPushAutoConfiguration.class,
            SsfReceiverPushSecurityAutoConfiguration.class, SsfReceiverResourceServerAutoConfiguration.class,
            SsfReceiverOidcClientAutoConfiguration.class);

    private static final AutoConfigurations REST_CLIENT = AutoConfigurations.of(HttpClientAutoConfiguration.class,
            ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class);

    private static final AutoConfigurations JDBC = AutoConfigurations.of(DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class);

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
        .withConfiguration(SSF_RECEIVER)
        .withConfiguration(
                AutoConfigurations.of(DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
        .withPropertyValues("easyssf.receiver.transmitter-issuer=https://idp.example/realms/test");

    @Test
    void configuresReceiverWithPushEndpointAndBothIntegrations() {
        this.webContextRunner.run((context) -> {
            assertThat(context).hasSingleBean(SsfSetVerifier.class);
            assertThat(context).hasSingleBean(SsfJtiDedupStore.class);
            assertThat(context).hasSingleBean(SsfSetProcessor.class);
            assertThat(context).hasSingleBean(SsfPushEndpoint.class);
            assertThat(context).hasBean("ssfPushRouterFunction");
            assertThat(context).hasSingleBean(SsfRevokedTokenValidator.class);
            assertThat(context).hasSingleBean(SsfTokenRevocationEventHandler.class);
            assertThat(context).hasSingleBean(SsfSessionTerminator.class);
            assertThat(context).hasSingleBean(SsfSessionTerminationEventHandler.class);
        });
    }

    @Test
    void pushFilterChainDoesNotReplaceDefaultSecurity() {
        this.webContextRunner.run((context) -> {
            assertThat(context).hasBean("ssfPushSecurityFilterChain");
            assertThat(context).hasBean("defaultSecurityFilterChain");
            assertThat(filterChainNames(context)).first().isEqualTo("ssfPushSecurityFilterChain");
        });
    }

    @Test
    void pushFilterChainPrecedesFilterChainOfApplication() {
        this.webContextRunner.withUserConfiguration(ApplicationSecurityConfiguration.class).run((context) -> {
            assertThat(context).doesNotHaveBean("defaultSecurityFilterChain");
            assertThat(filterChainNames(context)).containsExactly("ssfPushSecurityFilterChain",
                    "applicationSecurityFilterChain");
        });
    }

    @Test
    void pushFilterChainCanBeSwitchedOff() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.push.security.enabled=false").run((context) -> {
            assertThat(context).hasSingleBean(SsfPushEndpoint.class);
            assertThat(context).doesNotHaveBean("ssfPushSecurityFilterChain");
        });
    }

    @Test
    void pushEndpointCanBeSwitchedOff() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.push.enabled=false").run((context) -> {
            assertThat(context).hasSingleBean(SsfSetProcessor.class);
            assertThat(context).doesNotHaveBean(SsfPushEndpoint.class);
            assertThat(context).doesNotHaveBean("ssfPushRouterFunction");
            assertThat(context).doesNotHaveBean("ssfPushSecurityFilterChain");
        });
    }

    @Test
    void integrationsCanBeSwitchedOff() {
        this.webContextRunner
            .withPropertyValues("easyssf.receiver.resource-server.enabled=false",
                    "easyssf.receiver.oidc-client.enabled=false", "easyssf.receiver.dedup.enabled=false")
            .run((context) -> {
                assertThat(context).hasSingleBean(SsfPushEndpoint.class);
                assertThat(context).doesNotHaveBean(SsfRevokedTokenValidator.class);
                assertThat(context).doesNotHaveBean(SsfSessionTerminator.class);
                assertThat(context).doesNotHaveBean(SsfJtiDedupStore.class);
            });
    }

    @Test
    void receiverCanBeSwitchedOff() {
        new WebApplicationContextRunner().withConfiguration(SSF_RECEIVER)
            .withPropertyValues("easyssf.receiver.enabled=false")
            .run((context) -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SsfSetProcessor.class);
                assertThat(context).doesNotHaveBean(SsfPushEndpoint.class);
                assertThat(context).doesNotHaveBean(SsfRevokedTokenValidator.class);
            });
    }

    @Test
    void failsWithoutTransmitterIssuer() {
        new WebApplicationContextRunner().withConfiguration(SSF_RECEIVER).run((context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                .hasMessageContaining("easyssf.receiver.transmitter-issuer");
        });
    }

    @Test
    void failsWithInvalidPushEndpointPath() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.push.endpoint-path=ssf/push").run((context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("easyssf.receiver.push.endpoint-path");
        });
    }

    @Test
    void applicationCanProvideItsOwnStores() {
        this.webContextRunner.withUserConfiguration(CustomStoresConfiguration.class).run((context) -> {
            assertThat(context).hasSingleBean(SsfTokenRevocationStore.class);
            assertThat(context).doesNotHaveBean(InMemorySsfTokenRevocationStore.class);
            assertThat(context).hasSingleBean(SsfJtiDedupStore.class);
            assertThat(context).hasSingleBean(SsfSessionTerminator.class);
            assertThat(context.getBean(SsfSessionTerminator.class))
                .isSameAs(context.getBean("customSessionTerminator"));
        });
    }

    @Test
    void nonWebApplicationGetsNoPushEndpoint() {
        new ApplicationContextRunner().withConfiguration(SSF_RECEIVER)
            .withPropertyValues("easyssf.receiver.transmitter-issuer=https://idp.example/realms/test")
            .run((context) -> {
                assertThat(context).hasSingleBean(SsfSetProcessor.class);
                assertThat(context).doesNotHaveBean(SsfPushEndpoint.class);
                assertThat(context).doesNotHaveBean(SsfSessionTerminator.class);
                assertThat(context).hasSingleBean(SsfRevokedTokenValidator.class);
            });
    }

    @Test
    void userAgentForCallsToTheTransmitterIsConfigurable() {
        this.webContextRunner
            .run((context) -> assertThat(context.getBean(JdkSsfHttpClient.class).getUserAgent()).isNull());
        this.webContextRunner.withPropertyValues("easyssf.receiver.http.user-agent=my-receiver/1.0")
            .run((context) -> assertThat(context.getBean(JdkSsfHttpClient.class).getUserAgent())
                .isEqualTo("my-receiver/1.0"));
    }

    @Test
    void transmitterIsCalledWithRestClientOfTheApplication() {
        try (TestTransmitter transmitter = new TestTransmitter()) {
            this.webContextRunner.withConfiguration(REST_CLIENT)
                .withUserConfiguration(RecordingRestClientCustomizerConfiguration.class)
                .withPropertyValues("easyssf.receiver.http.user-agent=my-receiver/1.0")
                .run((context) -> {
                    assertThat(context).hasSingleBean(SsfHttpClient.class).hasSingleBean(RestClientSsfHttpClient.class);
                    URI jwksUri = URI.create(transmitter.jwksUri());
                    assertThat(context.getBean(SsfHttpClient.class).execute(SsfHttpRequest.get(jwksUri)).status())
                        .isEqualTo(200);
                    // customizers of the application apply to the calls of the receiver
                    assertThat(context.getBean("requestedUris", List.class)).containsExactly(jwksUri);
                    assertThat(transmitter.lastUserAgent()).isEqualTo("my-receiver/1.0");
                });
        }
    }

    @Test
    void restClientOfTheApplicationCanBeLeftAlone() {
        this.webContextRunner.withConfiguration(REST_CLIENT)
            .withPropertyValues("easyssf.receiver.http.use-rest-client=false")
            .run((context) -> assertThat(context).hasSingleBean(SsfHttpClient.class)
                .hasSingleBean(JdkSsfHttpClient.class));
    }

    @Test
    void stateIsKeptInMemoryWithoutJdbcTemplate() {
        this.webContextRunner.run((context) -> {
            assertThat(context).hasSingleBean(InMemorySsfJtiDedupStore.class);
            assertThat(context).hasSingleBean(InMemorySsfTokenRevocationStore.class);
        });
    }

    @Test
    void stateIsKeptInTheDatabaseWhenApplicationHasJdbcTemplate() {
        this.webContextRunner.withConfiguration(JDBC).run((context) -> {
            assertThat(context).hasSingleBean(SsfJtiDedupStore.class).hasSingleBean(JdbcSsfJtiDedupStore.class);
            assertThat(context).hasSingleBean(SsfTokenRevocationStore.class)
                .hasSingleBean(JdbcSsfTokenRevocationStore.class);
            // the tables are created in an embedded database
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM EASYSSF_PROCESSED_SET", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM EASYSSF_REVOCATION", Integer.class)).isZero();
        });
    }

    @Test
    void jdbcStoresArePurgedPeriodicallyUnlessSwitchedOff() {
        this.webContextRunner.withConfiguration(JDBC).run((context) -> {
            JdbcSsfStoreCleanup cleanup = context.getBean(JdbcSsfStoreCleanup.class);
            assertThat(cleanup.isEnabled()).isTrue();
            assertThat(cleanup.isRunning()).isTrue();
        });
        this.webContextRunner.withConfiguration(JDBC)
            .withPropertyValues("easyssf.receiver.jdbc.cleanup-interval=0")
            .run((context) -> assertThat(context.getBean(JdbcSsfStoreCleanup.class).isRunning()).isFalse());
    }

    @Test
    void jdbcStoresUseConfiguredTablePrefix() {
        this.webContextRunner.withConfiguration(JDBC)
            .withPropertyValues("easyssf.receiver.jdbc.table-prefix=APP_")
            .run((context) -> assertThat(context.getBean(JdbcTemplate.class)
                .queryForObject("SELECT COUNT(*) FROM APP_REVOCATION", Integer.class)).isZero());
    }

    @Test
    void jdbcStoresCanBeSwitchedOff() {
        this.webContextRunner.withConfiguration(JDBC)
            .withPropertyValues("easyssf.receiver.jdbc.enabled=false")
            .run((context) -> {
                assertThat(context).hasSingleBean(InMemorySsfJtiDedupStore.class);
                assertThat(context).hasSingleBean(InMemorySsfTokenRevocationStore.class);
            });
    }

    @Test
    void failsOnStartupWhenTablesAreMissingAndNotToBeCreated() {
        this.webContextRunner.withConfiguration(JDBC)
            .withPropertyValues("easyssf.receiver.jdbc.initialize-schema=never")
            .run((context) -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("does not exist")
                    .hasMessageContaining("schema.sql")
                    .hasMessageContaining("easyssf.receiver.jdbc.enabled=false");
            });
    }

    @Test
    void storesOfTheApplicationTakePrecedenceOverJdbcStores() {
        this.webContextRunner.withConfiguration(JDBC)
            .withUserConfiguration(CustomStoresConfiguration.class)
            .run((context) -> {
                assertThat(context).doesNotHaveBean(JdbcSsfJtiDedupStore.class);
                assertThat(context).doesNotHaveBean(JdbcSsfTokenRevocationStore.class);
                assertThat(context).hasSingleBean(SsfTokenRevocationStore.class);
            });
    }

    @Test
    void streamIsNotLookedUpOrManagedByDefault() {
        this.webContextRunner.run((context) -> {
            assertThat(context).hasSingleBean(SsfStreamClient.class);
            assertThat(context).hasSingleBean(SsfReceiverStream.class);
            assertThat(context).doesNotHaveBean(SsfStreamRegistrar.class);
            assertThat(context).doesNotHaveBean(SsfPoller.class);
            assertThat(context).doesNotHaveBean(SsfReceiverMetrics.class);
        });
    }

    @Test
    void streamIsManagedByReceiverWhenAsked() {
        this.webContextRunner
            .withPropertyValues("easyssf.receiver.stream.management=receiver",
                    "easyssf.receiver.push.delivery-endpoint-url=https://app.example/ssf/push")
            .run((context) -> assertThat(context).hasSingleBean(SsfStreamRegistrar.class));
    }

    @Test
    void streamCreatedAtTransmitterIsLookedUpWhenItsIdIsConfigured() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.stream.id=stream-1")
            .run((context) -> assertThat(context).hasSingleBean(SsfStreamRegistrar.class));
    }

    @Test
    void pushStreamManagedByReceiverNeedsDeliveryEndpointUrl() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.stream.management=receiver").run((context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("easyssf.receiver.push.delivery-endpoint-url");
        });
    }

    @Test
    void pollDeliveryReplacesPushEndpoint() {
        this.webContextRunner
            .withPropertyValues("easyssf.receiver.delivery-method=poll", "easyssf.receiver.poll.auto-startup=false",
                    "easyssf.receiver.poll.endpoint-url=https://idp.example/realms/test/poll")
            .run((context) -> {
                assertThat(context).hasSingleBean(SsfPoller.class);
                assertThat(context.getBean(SsfPoller.class).isRunning()).isFalse();
                assertThat(context).doesNotHaveBean(SsfPushEndpoint.class);
                assertThat(context).doesNotHaveBean("ssfPushSecurityFilterChain");
                assertThat(context).hasBean("defaultSecurityFilterChain");
            });
    }

    @Test
    void pollDeliveryNeedsPollEndpoint() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.delivery-method=poll").run((context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("easyssf.receiver.poll.endpoint-url");
        });
    }

    @Test
    void oauth2TokenUriNeedsClientCredentials() {
        this.webContextRunner.withPropertyValues("easyssf.receiver.oauth2.token-uri=https://idp.example/token")
            .run((context) -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("easyssf.receiver.oauth2.client-id");
            });
    }

    @Test
    void recordsMetricsWhenApplicationHasMeterRegistry() {
        this.webContextRunner.withUserConfiguration(MeterRegistryConfiguration.class)
            .run((context) -> assertThat(context).hasSingleBean(MicrometerSsfReceiverMetrics.class));
        this.webContextRunner.withUserConfiguration(MeterRegistryConfiguration.class)
            .withPropertyValues("easyssf.receiver.metrics.enabled=false")
            .run((context) -> assertThat(context).doesNotHaveBean(SsfReceiverMetrics.class));
    }

    private static List<String> filterChainNames(org.springframework.context.ApplicationContext context) {
        return context.getBeanProvider(SecurityFilterChain.class)
            .orderedStream()
            .map((filterChain) -> context.getBeansOfType(SecurityFilterChain.class)
                .entrySet()
                .stream()
                .filter((entry) -> entry.getValue() == filterChain)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow())
            .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationSecurityConfiguration {

        @Bean
        SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) {
            http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated());
            return http.build();
        }

    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingRestClientCustomizerConfiguration {

        @Bean
        List<URI> requestedUris() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        RestClientCustomizer recordingRestClientCustomizer(List<URI> requestedUris) {
            return (builder) -> builder.requestInterceptor((request, body, execution) -> {
                requestedUris.add(request.getURI());
                return execution.execute(request, body);
            });
        }

    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStoresConfiguration {

        @Bean
        SsfTokenRevocationStore customRevocationStore() {
            return mock(SsfTokenRevocationStore.class);
        }

        @Bean
        SsfJtiDedupStore customDedupStore() {
            return mock(SsfJtiDedupStore.class);
        }

        @Bean
        SsfSessionTerminator customSessionTerminator() {
            return (subject) -> 0;
        }

    }

}

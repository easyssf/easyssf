package org.easyssf.receiver.spring.boot.autoconfigure;

import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.web.SsfPushEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@link AutoConfiguration Auto-configuration} that makes the push endpoint reachable for
 * the transmitter in an application secured with Spring Security. The transmitter has no
 * user session, CSRF token or access token for this application; a SET is authenticated
 * by its signature (and the optional {@code Authorization} header).
 *
 * <p>
 * Spring Boot only applies its default security if the application context has no
 * {@link SecurityFilterChain}. This auto-configuration is therefore processed after the
 * ones that contribute a default, so that adding a filter chain for the push endpoint
 * does not switch off the security of the rest of the application.
 */
@AutoConfiguration(after = SsfReceiverPushAutoConfiguration.class, afterName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerAutoConfiguration",
        "org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration" })
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass({ SecurityFilterChain.class, HttpSecurity.class })
@ConditionalOnBean({ SsfPushEndpoint.class, HttpSecurity.class })
@ConditionalOnBooleanProperty(name = "easyssf.receiver.push.security.enabled", matchIfMissing = true)
public final class SsfReceiverPushSecurityAutoConfiguration {

    /**
     * Order of the filter chain for the push endpoint. It is consulted before the filter
     * chains of the application, which usually match any request.
     */
    public static final int FILTER_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Bean
    @Order(FILTER_CHAIN_ORDER)
    SecurityFilterChain ssfPushSecurityFilterChain(HttpSecurity http, SsfReceiverProperties properties) {
        http.securityMatcher(SsfReceiverPushAutoConfiguration.pushEndpointPath(properties));
        http.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);
        http.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

}

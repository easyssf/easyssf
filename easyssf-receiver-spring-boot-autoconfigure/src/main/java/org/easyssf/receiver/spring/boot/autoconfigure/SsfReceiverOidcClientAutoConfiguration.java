package org.easyssf.receiver.spring.boot.autoconfigure;

import jakarta.servlet.http.HttpSessionListener;

import org.easyssf.receiver.session.SsfSessionTerminationEventHandler;
import org.easyssf.receiver.session.SsfSessionTerminator;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.oidcclient.HttpSessionSsfSessionTerminator;
import org.easyssf.receiver.spring.boot.oidcclient.OidcSsfSessionMatcher;
import org.easyssf.receiver.spring.boot.oidcclient.SsfSessionMatcher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * {@link AutoConfiguration Auto-configuration} that lets an application that logs users
 * in with OpenID Connect terminate their local sessions when the identity provider
 * revokes a session or changes a credential.
 */
@AutoConfiguration(after = SsfReceiverAutoConfiguration.class)
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ ClientRegistration.class, OidcUser.class, HttpSessionSecurityContextRepository.class,
        HttpSessionListener.class })
@ConditionalOnBean(SsfSetProcessor.class)
@ConditionalOnBooleanProperty(name = "easyssf.receiver.oidc-client.enabled", matchIfMissing = true)
public final class SsfReceiverOidcClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SsfSessionMatcher.class)
    OidcSsfSessionMatcher ssfSessionMatcher() {
        return new OidcSsfSessionMatcher();
    }

    @Bean
    @ConditionalOnMissingBean(SsfSessionTerminator.class)
    HttpSessionSsfSessionTerminator ssfSessionTerminator(SsfSessionMatcher sessionMatcher) {
        return new HttpSessionSsfSessionTerminator(sessionMatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    SsfSessionTerminationEventHandler ssfSessionTerminationEventHandler(SsfSessionTerminator sessionTerminator,
            SsfReceiverProperties properties) {
        SsfReceiverProperties.OidcClient oidcClient = properties.getOidcClient();
        return new SsfSessionTerminationEventHandler(sessionTerminator, oidcClient.getSessionEventTypes(),
                oidcClient.getUserEventTypes());
    }

}

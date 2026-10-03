package org.easyssf.receiver.spring.boot.autoconfigure;

import org.easyssf.receiver.revocation.InMemorySsfTokenRevocationStore;
import org.easyssf.receiver.revocation.SsfTokenRevocationEventHandler;
import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.spring.boot.SsfReceiverProperties;
import org.easyssf.receiver.spring.boot.resourceserver.SsfRevokedTokenValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * {@link AutoConfiguration Auto-configuration} that lets a resource server reject the
 * access tokens of sessions and users that were revoked by a security event.
 */
@AutoConfiguration(after = SsfReceiverAutoConfiguration.class)
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass({ BearerTokenAuthenticationToken.class, Jwt.class })
@ConditionalOnBean(SsfSetProcessor.class)
@ConditionalOnBooleanProperty(name = "easyssf.receiver.resource-server.enabled", matchIfMissing = true)
public final class SsfReceiverResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SsfTokenRevocationStore.class)
    InMemorySsfTokenRevocationStore ssfTokenRevocationStore(SsfReceiverProperties properties) {
        return new InMemorySsfTokenRevocationStore(properties.getResourceServer().getRevocationTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    SsfRevokedTokenValidator ssfRevokedTokenValidator(SsfTokenRevocationStore revocationStore) {
        return new SsfRevokedTokenValidator(revocationStore);
    }

    @Bean
    @ConditionalOnMissingBean
    SsfTokenRevocationEventHandler ssfTokenRevocationEventHandler(SsfTokenRevocationStore revocationStore,
            SsfReceiverProperties properties) {
        return new SsfTokenRevocationEventHandler(revocationStore, properties.getResourceServer().getEventTypes());
    }

}

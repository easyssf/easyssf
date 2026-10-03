package org.easyssf.examples.oidcclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The security configuration of the application itself. It does not mention the push
 * endpoint: the starter contributes a separate filter chain for it that takes precedence
 * over this one.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository clientRegistrations) {
        // logging out of the application also logs the user out of Keycloak
        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler = new OidcClientInitiatedLogoutSuccessHandler(
                clientRegistrations);
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");

        http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated());
        http.oauth2Login((login) -> login.failureHandler(loggingFailureHandler()));
        // the session check of the page expects a 401 instead of a redirect to the login
        http.exceptionHandling((exceptions) -> exceptions.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/auth/check")));
        http.logout((logout) -> logout.logoutSuccessHandler(logoutSuccessHandler));
        return http.build();
    }

    /**
     * The default login page only shows "Invalid credentials" when the login with
     * Keycloak fails. Log the reason (for example an error Keycloak returned, or an
     * invalid ID token) before redirecting to the login page like the default handler
     * does.
     */
    private static AuthenticationFailureHandler loggingFailureHandler() {
        SimpleUrlAuthenticationFailureHandler redirectToLoginPage = new SimpleUrlAuthenticationFailureHandler(
                "/login?error");
        return (request, response, exception) -> {
            logger.warn("Login with Keycloak failed: {}", exception.getMessage());
            logger.debug("Login failure", exception);
            redirectToLoginPage.onAuthenticationFailure(request, response, exception);
        };
    }

}

package org.easyssf.receiver.spring.boot;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * An application that logs users in with OpenID Connect, relies on Spring Boot's default
 * security configuration and receives CAEP events on a custom push endpoint.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "easyssf.receiver.push.endpoint-path=/signals/events",
        "spring.security.oauth2.client.registration.idp.client-id=test-client",
        "spring.security.oauth2.client.registration.idp.client-secret=secret",
        "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.idp.scope=openid",
        "spring.security.oauth2.client.provider.idp.authorization-uri=http://127.0.0.1:1/auth",
        "spring.security.oauth2.client.provider.idp.token-uri=http://127.0.0.1:1/token",
        "spring.security.oauth2.client.provider.idp.jwk-set-uri=http://127.0.0.1:1/jwks",
        "spring.security.oauth2.client.provider.idp.user-name-attribute=sub" })
class OidcClientIntegrationTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void applicationStillRequiresLogin() throws Exception {
        HttpResponse<String> response = get("/me", null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
            .hasValueSatisfying((location) -> assertThat(location).endsWith("/oauth2/authorization/idp"));
    }

    @Test
    void sessionRevokedEventTerminatesThatSessionOnly() throws Exception {
        String revoked = login("alice", "sid-1");
        String otherSession = login("alice", "sid-2");
        assertThat(get("/me", revoked).body()).isEqualTo("alice");

        HttpResponse<String> pushed = push(
                transmitter.set("CaepSessionRevoked", complex(issSub(transmitter.issuer(), "alice"), opaque("sid-1"))));

        assertThat(pushed.statusCode()).isEqualTo(202);
        assertThat(get("/me", revoked).statusCode()).isEqualTo(302);
        assertThat(get("/me", otherSession).body()).isEqualTo("alice");
    }

    @Test
    void sessionRevokedEventForUserTerminatesAllSessionsOfThatUser() throws Exception {
        String first = login("bob", "sid-3");
        String second = login("bob", "sid-4");
        String otherUser = login("carol", "sid-5");

        assertThat(push(transmitter.set("CaepSessionRevoked", issSub(transmitter.issuer(), "bob"))).statusCode())
            .isEqualTo(202);

        assertThat(get("/me", first).statusCode()).isEqualTo(302);
        assertThat(get("/me", second).statusCode()).isEqualTo(302);
        assertThat(get("/me", otherUser).body()).isEqualTo("carol");
    }

    @Test
    void keycloakLogoutOfAllSessionsTerminatesAllSessionsOfThatUser() throws Exception {
        String first = login("judy", "sid-13");
        String second = login("judy", "sid-14");
        String otherUser = login("mallory", "sid-15");

        assertThat(push(
                transmitter.set("CaepSessionRevoked", complex(issSub(transmitter.issuer(), "judy"), opaque("ALL"))))
            .statusCode()).isEqualTo(202);

        assertThat(get("/me", first).statusCode()).isEqualTo(302);
        assertThat(get("/me", second).statusCode()).isEqualTo(302);
        assertThat(get("/me", otherUser).body()).isEqualTo("mallory");
    }

    @Test
    void sessionRevokedEventOfAnotherIssuerDoesNotTerminateSessions() throws Exception {
        String session = login("dave", "sid-6");
        assertThat(push(transmitter.set("CaepSessionRevoked", issSub("https://other.example", "dave"))).statusCode())
            .isEqualTo(202);
        assertThat(get("/me", session).body()).isEqualTo("dave");
    }

    @Test
    void credentialChangeEventTerminatesAllSessionsOfThatUser() throws Exception {
        String first = login("erin", "sid-7");
        String second = login("erin", "sid-8");
        String otherUser = login("frank", "sid-9");
        Map<String, Object> event = Map.of("credential_type", "password", "change_type", "update", "event_timestamp",
                Instant.now().getEpochSecond());

        // even when the event names a session, a credential change concerns the user
        assertThat(push(transmitter.signSet(transmitter
            .setClaims("CaepCredentialChange", complex(issSub(transmitter.issuer(), "erin"), opaque("sid-7")), event)
            .build())).statusCode()).isEqualTo(202);

        assertThat(get("/me", first).statusCode()).isEqualTo(302);
        assertThat(get("/me", second).statusCode()).isEqualTo(302);
        assertThat(get("/me", otherUser).body()).isEqualTo("frank");
    }

    @Test
    void emailSubjectTerminatesSessionsOfThatUser() throws Exception {
        String session = login("grace", "sid-10");
        String otherUser = login("heidi", "sid-11");

        assertThat(push(transmitter.set("CaepSessionRevoked", email("GRACE@example.com"))).statusCode()).isEqualTo(202);

        assertThat(get("/me", session).statusCode()).isEqualTo(302);
        assertThat(get("/me", otherUser).body()).isEqualTo("heidi");
    }

    @Test
    void otherEventsDoNotTerminateSessions() throws Exception {
        String session = login("ivan", "sid-12");
        assertThat(push(transmitter.set("CaepTokenClaimsChange", issSub(transmitter.issuer(), "ivan"))).statusCode())
            .isEqualTo(202);
        assertThat(get("/me", session).body()).isEqualTo("ivan");
    }

    @Test
    void pushEndpointIsOnlyMountedOnConfiguredPath() throws Exception {
        String set = transmitter.set("CaepSessionRevoked", opaque("sid-0"));
        HttpResponse<String> response = post("/ssf/push", set);
        assertThat(response.statusCode()).isNotEqualTo(202);
    }

    /**
     * Establishes a session for a user as if they had logged in at the identity provider
     * and returns its cookie.
     */
    private String login(String subject, String sessionId) throws Exception {
        String query = "sub=" + subject + "&sid=" + sessionId + "&iss="
                + URLEncoder.encode(transmitter.issuer(), StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/test-login?" + query, null);
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private HttpResponse<String> push(String set) throws Exception {
        return post("/signals/events", set);
    }

    private HttpResponse<String> post(String path, String set) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/secevent+jwt")
            .POST(HttpRequest.BodyPublishers.ofString(set))
            .build();
        return this.http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + this.port + path);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MeController.class)
    static class OidcClientApplication {

        /**
         * Stands in for the OpenID Connect login: stores the authentication of an OIDC
         * user in a new session, the way Spring Security does after a login.
         */
        @Bean
        FilterRegistrationBean<Filter> testLoginFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain filterChain) throws ServletException, IOException {
                    String subject = request.getParameter("sub");
                    OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                        .issuer(request.getParameter("iss"))
                        .subject(subject)
                        .claim("sid", request.getParameter("sid"))
                        .claim("email", subject + "@example.com")
                        .build();
                    List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("OIDC_USER");
                    OidcUser user = new DefaultOidcUser(authorities, idToken);
                    request.getSession(true)
                        .setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                                new SecurityContextImpl(new OAuth2AuthenticationToken(user, authorities, "idp")));
                    response.setStatus(200);
                }
            });
            registration.addUrlPatterns("/test-login");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

    }

    @RestController
    static class MeController {

        @GetMapping("/me")
        String me(@AuthenticationPrincipal OidcUser user) {
            return user.getSubject();
        }

    }

}

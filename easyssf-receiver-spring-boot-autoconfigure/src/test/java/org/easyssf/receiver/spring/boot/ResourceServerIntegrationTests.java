package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.easyssf.receiver.spring.boot.http.RestClientSsfHttpClient;
import org.easyssf.receiver.spring.boot.jdbc.JdbcSsfTokenRevocationStore;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * A resource server that relies on Spring Boot's default security configuration, receives
 * CAEP events on the push endpoint and has a database and a RestClient.Builder.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = { "easyssf.receiver.expected-audience=" + TestTransmitter.AUDIENCE,
                "easyssf.receiver.push.expected-auth-header=Bearer push-secret" })
class ResourceServerIntegrationTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RecordingHandler recordingHandler;

    @Autowired
    private SsfTokenRevocationStore revocationStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SsfHttpClient httpClient;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", transmitter::jwksUri);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void apiStillRequiresAccessToken() throws Exception {
        assertThat(get("/api/me", null).statusCode()).isEqualTo(401);
        HttpResponse<String> response = get("/api/me", transmitter.accessToken("alice", "session-0", Instant.now()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("alice");
    }

    @Test
    void sessionRevokedEventInvalidatesAccessTokensOfThatSession() throws Exception {
        String revoked = transmitter.accessToken("bob", "session-1", Instant.now());
        String otherSession = transmitter.accessToken("bob", "session-2", Instant.now());
        assertThat(get("/api/me", revoked).statusCode()).isEqualTo(200);

        HttpResponse<String> pushed = push(transmitter.set("CaepSessionRevoked",
                complex(issSub(transmitter.issuer(), "bob"), opaque("session-1"))));

        assertThat(pushed.statusCode()).isEqualTo(202);
        assertThat(pushed.body()).isEmpty();
        HttpResponse<String> rejected = get("/api/me", revoked);
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(rejected.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                (challenge) -> assertThat(challenge).contains("invalid_token").contains("The token was revoked"));
        assertThat(get("/api/me", otherSession).statusCode()).isEqualTo(200);
    }

    @Test
    void sessionRevokedEventForUserInvalidatesAllAccessTokensIssuedBefore() throws Exception {
        String first = transmitter.accessToken("carol", "session-3", Instant.now().minusSeconds(30));
        String second = transmitter.accessToken("carol", "session-4", Instant.now().minusSeconds(20));
        assertThat(get("/api/me", first).statusCode()).isEqualTo(200);

        assertThat(push(transmitter.set("CaepSessionRevoked", issSub(transmitter.issuer(), "carol"))).statusCode())
            .isEqualTo(202);

        assertThat(get("/api/me", first).statusCode()).isEqualTo(401);
        assertThat(get("/api/me", second).statusCode()).isEqualTo(401);
        String afterLogin = transmitter.accessToken("carol", "session-5", Instant.now().plusSeconds(5));
        assertThat(get("/api/me", afterLogin).statusCode()).isEqualTo(200);
    }

    @Test
    void duplicateSetIsAcknowledgedButHandledOnce() throws Exception {
        String set = transmitter.set("CaepSessionRevoked", opaque("session-6"));
        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();
        assertThat(push(set).statusCode()).isEqualTo(202);
        assertThat(push(set).statusCode()).isEqualTo(202);
        assertThat(this.recordingHandler.handledSets).containsOnlyOnce(jti);
    }

    @Test
    void transmitterIsCalledWithRestClientOfTheApplication() {
        assertThat(this.httpClient).isInstanceOf(RestClientSsfHttpClient.class);
    }

    @Test
    void revocationsAndProcessedSetsAreKeptInTheDatabaseOfTheApplication() throws Exception {
        assertThat(this.revocationStore).isInstanceOf(JdbcSsfTokenRevocationStore.class);
        String set = transmitter.set("CaepSessionRevoked", opaque("session-9"));

        assertThat(push(set).statusCode()).isEqualTo(202);

        assertThat(this.jdbc.queryForList("SELECT KIND FROM EASYSSF_REVOCATION WHERE ID = 'session-9'", String.class))
            .containsExactlyInAnyOrder("SESSION", "SUBJECT");
        assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM EASYSSF_PROCESSED_SET WHERE JTI = ?", Integer.class,
                SignedJWT.parse(set).getJWTClaimsSet().getJWTID()))
            .isEqualTo(1);
    }

    @Test
    void failingHandlerMakesTransmitterDeliverSetAgain() throws Exception {
        String set = transmitter.signSet(
                transmitter.setClaims("CaepSessionRevoked", opaque("session-8")).claim("txn", "fail-once").build());
        String jti = SignedJWT.parse(set).getJWTClaimsSet().getJWTID();

        assertThat(push(set).statusCode()).isEqualTo(500);
        assertThat(push(set).statusCode()).isEqualTo(202);

        assertThat(this.recordingHandler.handledSets.stream().filter(jti::equals)).hasSize(2);
        String token = transmitter.accessToken("erin", "session-8", Instant.now());
        assertThat(get("/api/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsPushWithoutExpectedAuthorizationHeader() throws Exception {
        String set = transmitter.set("CaepSessionRevoked", opaque("session-7"));
        HttpResponse<String> missing = push(set, null);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(error(missing)).containsEntry("err", "authentication_failed");
        assertThat(push(set, "Bearer wrong-secret").statusCode()).isEqualTo(401);
        String token = transmitter.accessToken("session-7", "session-7", Instant.now());
        assertThat(get("/api/me", token).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidSetWithErrorDocument() throws Exception {
        HttpResponse<String> malformed = push("not-a-set");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.headers().firstValue("Content-Type"))
            .hasValueSatisfying((contentType) -> assertThat(contentType).startsWith("application/json"));
        assertThat(malformed.headers().firstValue("Content-Language")).hasValue("en");
        assertThat(error(malformed)).containsEntry("err", "invalid_request").containsKey("description");

        HttpResponse<String> wrongAudience = push(transmitter.signSet(
                transmitter.setClaims("CaepSessionRevoked", opaque("x")).audience("https://other.example").build()));
        assertThat(wrongAudience.statusCode()).isEqualTo(400);
        assertThat(error(wrongAudience)).containsEntry("err", "invalid_audience");

        HttpResponse<String> forged = push(TestTransmitter.sign(TestTransmitter.generateKey(2048, "test-key"),
                new JOSEObjectType("secevent+jwt"), transmitter.setClaims("CaepSessionRevoked", opaque("x")).build()));
        assertThat(forged.statusCode()).isEqualTo(400);
        assertThat(error(forged)).containsEntry("err", "invalid_key");
    }

    private HttpResponse<String> push(String set) throws Exception {
        return push(set, "Bearer push-secret");
    }

    private HttpResponse<String> push(String set, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/ssf/push"))
            .header("Content-Type", "application/secevent+jwt")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(set));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + this.port + path);
    }

    private static Map<String, Object> error(HttpResponse<String> response) throws Exception {
        return JSONObjectUtils.parse(response.body());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MeController.class)
    static class ResourceServerApplication {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        SsfEventHandler failingOnceHandler() {
            AtomicBoolean failed = new AtomicBoolean();
            return (eventContext) -> {
                if ("fail-once".equals(eventContext.eventToken().txn()) && failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("simulated failure");
                }
            };
        }

    }

    static class RecordingHandler implements SsfEventHandler {

        private final List<String> handledSets = new CopyOnWriteArrayList<>();

        @Override
        public void handle(SsfEventContext eventContext) {
            this.handledSets.add(eventContext.eventToken().jti());
        }

    }

    @RestController
    static class MeController {

        @GetMapping("/api/me")
        String me(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

    }

}

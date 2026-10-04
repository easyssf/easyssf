package org.easyssf.examples.scim;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.nimbusds.jwt.JWTClaimsSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;

/**
 * Runs the application against the in-process transmitter, without the {@code demo}
 * profile, and plays the life of a user.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = { "easyssf.receiver.poll.interval=200ms", "easyssf.receiver.poll.initial-delay=100ms" })
class ExampleScimProvisioningApplicationTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private static final String ALICE = "/Users/2b2f880af6674ac284bae9381673d462";

    @Autowired
    private UserDirectory directory;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void transmitter(DynamicPropertyRegistry registry) {
        registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @BeforeEach
    void waitForTheStream() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !transmitter.streams().isEmpty());
    }

    @Test
    void mirrorsTheLifeOfAUser() {
        transmit(SsfEventTypes.SCIM_PROV_CREATE_FULL,
                Map.of("version", "1", "data",
                        Map.of("userName", "alice", "name", Map.of("givenName", "Alice", "familyName", "Adams"),
                                "emails", List.of(Map.of("value", "alice@example.com")))));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            User alice = this.directory.find("2b2f880af6674ac284bae9381673d462");
            assertThat(alice).isNotNull();
            assertThat(alice.userName()).isEqualTo("alice");
            assertThat(alice.displayName()).isEqualTo("Alice Adams");
            assertThat(alice.emails()).containsExactly("alice@example.com");
            assertThat(alice.externalId()).isEqualTo("alice");
            assertThat(alice.active()).isTrue();
            assertThat(alice.version()).isEqualTo("1");
        });
        ResponseEntity<String> users = get("/users");
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.getBody()).contains("\"userName\":\"alice\"").contains("\"displayName\":\"Alice Adams\"");

        transmit(SsfEventTypes.SCIM_PROV_PATCH_FULL,
                Map.of("version", "2", "data",
                        Map.of("Operations",
                                List.of(Map.of("op", "replace", "path", "displayName", "value", "Alice Baker"),
                                        Map.of("op", "replace", "value", Map.of("active", false))))));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            User alice = this.directory.find("2b2f880af6674ac284bae9381673d462");
            assertThat(alice.displayName()).isEqualTo("Alice Baker");
            assertThat(alice.active()).isFalse();
            assertThat(alice.version()).isEqualTo("2");
        });

        transmit(SsfEventTypes.SCIM_PROV_ACTIVATE, Map.of());
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(this.directory.find("2b2f880af6674ac284bae9381673d462").active()).isTrue());

        // a notice carries no data and changes nothing
        transmit(SsfEventTypes.SCIM_PROV_PUT_NOTICE, Map.of("attributes", List.of("userName")));
        transmit(SsfEventTypes.SCIM_PROV_DELETE, Map.of());
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(this.directory.find("2b2f880af6674ac284bae9381673d462")).isNull());
        assertThat(get("/users/2b2f880af6674ac284bae9381673d462").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ignoresGroups() {
        transmit(SsfEventTypes.SCIM_PROV_CREATE_FULL, "/Groups/176f397ec4c44b94b2cfcb759780b8c2", "crmUsers",
                Map.of("data", Map.of("displayName", "crmUsers")));
        transmit(SsfEventTypes.SCIM_PROV_CREATE_FULL, "/Users/44f6142df96bd6ab61e7521d9", null,
                Map.of("data", Map.of("userName", "jdoe")));
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(this.directory.find("44f6142df96bd6ab61e7521d9")).isNotNull());
        assertThat(this.directory.find("176f397ec4c44b94b2cfcb759780b8c2")).isNull();
        this.directory.remove("44f6142df96bd6ab61e7521d9");
    }

    private ResponseEntity<String> get(String path) {
        return RestClient.create("http://localhost:" + this.environment.getProperty("local.server.port"))
            .get()
            .uri(path)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
            })
            .toEntity(String.class);
    }

    private static void transmit(String eventType, Map<String, Object> payload) {
        transmit(eventType, ALICE, "alice", payload);
    }

    @SuppressWarnings("unchecked")
    private static void transmit(String eventType, String uri, String externalId, Map<String, Object> payload) {
        List<String> audience = (List<String>) transmitter.streams().get(0).get("aud");
        JWTClaimsSet claims = transmitter.setClaims(eventType, scim(uri, externalId), payload)
            .audience(audience)
            .build();
        transmitter.queueSet(transmitter.signSet(claims));
    }

}

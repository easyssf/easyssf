package org.easyssf.examples.scim;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The demo profile: the service provider API transmits SCIM Events that the receiver of
 * the same application mirrors, the walk-through of
 * {@code example-scim-provisioning.http}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "spring.profiles.active=demo",
        "demo.autoplay=false", "easyssf.receiver.poll.interval=200ms", "easyssf.receiver.poll.initial-delay=100ms" })
class DemoScimProviderTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

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

    @TestConfiguration
    static class DemoTransmitter {

        @Bean
        TestTransmitter demoTransmitter() {
            return transmitter;
        }

    }

    @Test
    @SuppressWarnings("unchecked")
    void requestsToTheDemoProviderAreMirroredByTheReceiver() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !transmitter.streams().isEmpty());
        RestClient rest = RestClient.create("http://localhost:" + this.environment.getProperty("local.server.port"));

        ResponseEntity<Map<String, Object>> created = rest.post()
            .uri("/demo/scim/Users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("userName", "carol", "externalId", "carol", "name",
                    Map.of("givenName", "Carol", "familyName", "Clark")))
            .retrieve()
            .toEntity(new ParameterizedTypeReference<>() {
            });
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> claims = (Map<String, Object>) created.getBody().get("claims");
        Map<String, Object> subject = (Map<String, Object>) claims.get("sub_id");
        assertThat(subject).containsEntry("format", "scim").containsEntry("externalId", "carol");
        String uri = (String) subject.get("uri");
        String id = uri.substring("/Users/".length());
        assertThat(((Map<String, Object>) claims.get("events")).keySet())
            .containsExactly("urn:ietf:params:scim:event:feed:add", "urn:ietf:params:scim:event:prov:create:full");
        assertThat(created.getHeaders().getLocation()).hasPath("/demo/scim" + uri);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            User carol = this.directory.find(id);
            assertThat(carol).isNotNull();
            assertThat(carol.displayName()).isEqualTo("Carol Clark");
            assertThat(carol.externalId()).isEqualTo("carol");
        });

        rest.patch()
            .uri("/demo/scim/Users/{id}?mode=notice", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("Operations", List.of(Map.of("op", "replace", "path", "phoneNumbers", "value", List.of()))))
            .retrieve()
            .toBodilessEntity();
        rest.post().uri("/demo/scim/Users/{id}/deactivate", id).retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(this.directory.find(id).active()).isFalse());
        assertThat(this.directory.find(id).version()).isEqualTo("1");

        rest.delete().uri("/demo/scim/Users/{id}", id).retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(this.directory.find(id)).isNull());

        List<Map<String, Object>> events = rest.get()
            .uri("/demo/scim/events")
            .retrieve()
            .body(new ParameterizedTypeReference<>() {
            });
        assertThat(events).hasSize(4);
        assertThat(events).allSatisfy((set) -> assertThat((String) set.get("set")).contains("."));
        assertThat(rest.get()
            .uri("/users/{id}", id)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
            })
            .toBodilessEntity()
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

}

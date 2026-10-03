package org.easyssf.receiver.spring.boot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.easyssf.test.TestTransmitter;
import org.easyssf.test.TestTransmitter.MetadataLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * The push endpoint of an application that started while the transmitter was not
 * available.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PushEndpointIntegrationTests {

    private static final TestTransmitter transmitter = new TestTransmitter(MetadataLocation.NONE);

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
    @Order(1)
    void asksForRedeliveryWhileTransmitterMetadataIsUnavailable() throws Exception {
        HttpResponse<String> response = push(transmitter.set("CaepSessionRevoked", opaque("session-1")));
        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    @Order(2)
    void acceptsSetsOnceTransmitterMetadataIsAvailable() throws Exception {
        transmitter.publishMetadata(MetadataLocation.OIDC_STYLE);
        HttpResponse<String> response = push(transmitter.set("CaepSessionRevoked", opaque("session-1")));
        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    @Order(3)
    void acknowledgesStreamVerificationEvent() throws Exception {
        String set = transmitter.signSet(transmitter.setClaims("SsfStreamVerification", null).build());
        assertThat(push(set).statusCode()).isEqualTo(202);
    }

    @Test
    void rejectsOversizedBody() throws Exception {
        assertThat(push("x".repeat(300 * 1024)).statusCode()).isEqualTo(413);
    }

    @Test
    void onlyAcceptsPost() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/ssf/push"))
            .GET()
            .build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValue("POST");
    }

    private HttpResponse<String> push(String set) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/ssf/push"))
            .header("Content-Type", "application/secevent+jwt")
            .POST(HttpRequest.BodyPublishers.ofString(set))
            .build();
        return this.http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ReceiverApplication {

    }

}

package org.easyssf.conformance.receiver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.receiver.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the scenarios against the test transmitter of the receiver library, with POLL
 * delivery so that the transmitter does not have to reach this application.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "server.ssl.enabled=false",
        "spring.ssl.bundle.pem.conformance-suite.truststore.certificate=classpath:test-ca.pem",
        "cts.transmitter.verify-hostname=true", "cts.delivery.method=poll", "cts.delivery.poll-interval=200ms",
        "cts.run.idle-timeout=2s", "cts.run.verification-timeout=5s", "cts.auth.client-id=" + TestTransmitter.CLIENT_ID,
        "cts.auth.client-secret=" + TestTransmitter.CLIENT_SECRET })
class ConformanceRunTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void transmitterProperties(DynamicPropertyRegistry registry) {
        registry.add("cts.transmitter.issuer", transmitter::issuer);
    }

    @BeforeEach
    void resetTransmitter() {
        transmitter.reset();
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void playsTheCaepInteropScenario() throws Exception {
        Map<String, Object> run = start("caep-interop");
        assertThat(run.get("status")).isIn("STARTING", "RUNNING");

        String streamId = awaitStreamAndVerificationRequest();
        String state = transmitter.verificationRequests().get(0);
        String verification = queueVerificationEvent(streamId, state);
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(verification));

        String sessionRevoked = queueSessionRevoked(streamId);
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(sessionRevoked));

        Map<String, Object> finished = awaitStatus("FINISHED");
        assertThat(transmitter.streams()).isEmpty();
        assertThat(log(finished)).anySatisfy((entry) -> assertThat(entry).contains("Read stream status"))
            .anySatisfy((entry) -> assertThat(entry).contains("Verification event").contains("accepted"))
            .anySatisfy((entry) -> assertThat(entry).contains("Deleted stream " + streamId));
        assertThat(transmitter.reportedErrors()).isEmpty();
    }

    @Test
    void rejectsVerificationEventWithWrongStateAndRequestsVerificationAgain() throws Exception {
        start("caep-interop");
        String streamId = awaitStreamAndVerificationRequest();
        String wrong = queueVerificationEvent(streamId, "made-up");
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.reportedErrors().containsKey(wrong));
        assertThat(transmitter.reportedErrors().get(wrong))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .containsEntry("err", "invalid_state");
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.verificationRequests().size() == 2);

        String right = queueVerificationEvent(streamId, transmitter.verificationRequests().get(1));
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(right));
        awaitStatus("FINISHED");
        assertThat(transmitter.streams()).isEmpty();
    }

    @Test
    void rejectsVerificationEventAboutAnotherStream() throws Exception {
        start("caep-interop");
        String streamId = awaitStreamAndVerificationRequest();
        String wrong = queueVerificationEvent("another-stream", transmitter.verificationRequests().get(0));
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.reportedErrors().containsKey(wrong));
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.verificationRequests().size() == 2);
        String right = queueVerificationEvent(streamId, transmitter.verificationRequests().get(1));
        await().atMost(Duration.ofSeconds(5)).until(() -> transmitter.acknowledgedSets().contains(right));
        awaitStatus("FINISHED");
    }

    @Test
    void refusesStreamWithAnotherIssuer() throws Exception {
        transmitter.setStreamIssuer("https://other.example");
        start("caep-interop");
        Map<String, Object> run = awaitStatus("REFUSED_STREAM");
        assertThat(transmitter.streams()).isEmpty();
        assertThat(transmitter.verificationRequests()).isEmpty();
        assertThat(log(run)).anySatisfy((entry) -> assertThat(entry).contains("Refusing the stream"));
    }

    @Test
    void createsAndDeletesTheStream() throws Exception {
        start("create-delete");
        awaitStatus("FINISHED");
        assertThat(transmitter.streams()).isEmpty();
        assertThat(transmitter.verificationRequests()).isEmpty();
    }

    @Test
    void managesStreamAndStatusAndSubjects() throws Exception {
        for (String scenario : List.of("stream-management", "status-update", "remove-subject")) {
            transmitter.reset();
            start(scenario);
            String streamId = awaitStreamAndVerificationRequest();
            queueVerificationEvent(streamId, transmitter.verificationRequests().get(0));
            Map<String, Object> run = awaitStatus("FINISHED");
            assertThat(transmitter.streams()).as(scenario).isEmpty();
            assertThat(log(run)).as(scenario)
                .anySatisfy((entry) -> assertThat(entry).containsAnyOf("Replaced stream", "Enabling the stream",
                        "Removed subject"));
        }
    }

    @Test
    void pushEndpointAnswersWithoutRun() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/ssf/push"))
            .header("Content-Type", "application/secevent+jwt")
            .POST(HttpRequest.BodyPublishers.ofString("x"))
            .build();
        // a run of another test may still be active, stop it first
        this.http.send(
                HttpRequest.newBuilder(uri("/cts/runs/current/stop")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        await().atMost(Duration.ofSeconds(5)).until(() -> !"RUNNING".equals(current().get("status")));
        assertThat(this.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
    }

    private String awaitStreamAndVerificationRequest() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !transmitter.verificationRequests().isEmpty());
        assertThat(transmitter.streams()).hasSize(1);
        return (String) transmitter.streams().get(0).get("stream_id");
    }

    private String queueVerificationEvent(String streamId, String state) {
        JWTClaimsSet claims = transmitter
            .setClaims("SsfStreamVerification", SsfSubjectIdentifiers.opaque(streamId), Map.of("state", state))
            .audience(audience())
            .build();
        transmitter.queueSet(transmitter.signSet(claims));
        return claims.getJWTID();
    }

    private String queueSessionRevoked(String streamId) {
        JWTClaimsSet claims = transmitter
            .setClaims("CaepSessionRevoked", SsfSubjectIdentifiers.issSub(transmitter.issuer(), "alice"))
            .audience(audience())
            .build();
        transmitter.queueSet(transmitter.signSet(claims));
        return claims.getJWTID();
    }

    @SuppressWarnings("unchecked")
    private static List<String> audience() {
        return (List<String>) transmitter.streams().get(0).get("aud");
    }

    private Map<String, Object> start(String scenario) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/cts/runs?scenario=" + scenario))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return JSONObjectUtils.parse(response.body());
    }

    private Map<String, Object> current() throws Exception {
        HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(uri("/cts/runs/current")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JSONObjectUtils.parse(response.body());
    }

    private Map<String, Object> awaitStatus(String status) throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() -> status.equals(current().get("status")));
        return current();
    }

    @SuppressWarnings("unchecked")
    private static List<String> log(Map<String, Object> run) {
        return ((List<Map<String, Object>>) run.get("log")).stream()
            .map((entry) -> (String) entry.get("message"))
            .toList();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + this.port + path);
    }

}

package org.easyssf.receiver.spring.boot.http;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.receiver.TestTransmitter;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class RestClientSsfHttpClientTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final SsfHttpClient httpClient = new RestClientSsfHttpClient(
            RestClient.builder().defaultHeader("User-Agent", "my-receiver/1.0").build());

    @BeforeEach
    void resetTransmitter() {
        transmitter.reset();
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void returnsResponseWhateverItsStatus() throws Exception {
        SsfHttpResponse found = this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())));
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.header("content-type")).isEqualTo("application/json");
        assertThat(found.body()).contains("\"keys\"");

        SsfHttpResponse unauthorized = this.httpClient
            .execute(SsfHttpRequest.get(URI.create(transmitter.issuer() + "/streams")));
        assertThat(unauthorized.status()).isEqualTo(401);
        assertThat(unauthorized.isSuccessful()).isFalse();
    }

    @Test
    void reportsFailedConnectionAsIOException() {
        assertThatIOException().isThrownBy(
                () -> this.httpClient.execute(SsfHttpRequest.get(URI.create("http://127.0.0.1:1/unreachable"))));
    }

    @Test
    void sendsUserAgentOfTheRestClientUnlessTheRequestHasOne() throws IOException {
        this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())));
        assertThat(transmitter.lastUserAgent()).isEqualTo("my-receiver/1.0");
        this.httpClient
            .execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())).withHeader("User-Agent", "special/2.0"));
        assertThat(transmitter.lastUserAgent()).isEqualTo("special/2.0");
    }

    @Test
    void sendsRequestsOfTheStreamClientIncludingPatch() {
        SsfStreamClient streamClient = new SsfStreamClient(this.httpClient, () -> TestTransmitter.ACCESS_TOKEN,
                new SsfTransmitterMetadataResolver(transmitter.issuer(), null, this.httpClient));
        SsfStreamConfiguration created = streamClient
            .createStream(SsfStreamConfiguration.poll(List.of("CaepSessionRevoked"), "rest client"));
        assertThat(created.description()).isEqualTo("rest client");
        SsfStreamConfiguration updated = streamClient.updateStream(created.streamId(),
                java.util.Map.of("description", "updated"));
        assertThat(updated.description()).isEqualTo("updated");
        streamClient.deleteStream(created.streamId());
        assertThat(streamClient.getStreams()).isEmpty();
    }

}

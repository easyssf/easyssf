package org.easyssf.receiver.http;

import java.net.URI;

import org.easyssf.receiver.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdkSsfHttpClientTests {

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final JdkSsfHttpClient httpClient = new JdkSsfHttpClient();

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void returnsResponseWhateverItsStatus() throws Exception {
        SsfHttpResponse found = this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())));
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.isSuccessful()).isTrue();
        assertThat(found.header("content-type")).isEqualTo("application/json");
        assertThat(found.body()).contains("\"keys\"");

        SsfHttpResponse missing = this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.issuer() + "/x")));
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.isSuccessful()).isFalse();
    }

    @Test
    void sendsUserAgentOfTheJdkByDefault() throws Exception {
        this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())));
        assertThat(transmitter.lastUserAgent()).startsWith("Java-http-client/");
        assertThat(this.httpClient.getUserAgent()).isNull();
    }

    @Test
    void sendsConfiguredUserAgent() throws Exception {
        this.httpClient.setUserAgent("my-receiver/1.0");
        this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())));
        assertThat(transmitter.lastUserAgent()).isEqualTo("my-receiver/1.0");

        this.httpClient.setUserAgent(" ");
        this.httpClient.execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())));
        assertThat(transmitter.lastUserAgent()).startsWith("Java-http-client/");
    }

    @Test
    void userAgentOfTheRequestTakesPrecedence() throws Exception {
        this.httpClient.setUserAgent("my-receiver/1.0");
        this.httpClient
            .execute(SsfHttpRequest.get(URI.create(transmitter.jwksUri())).withHeader("user-agent", "special/2.0"));
        assertThat(transmitter.lastUserAgent()).isEqualTo("special/2.0");
    }

}

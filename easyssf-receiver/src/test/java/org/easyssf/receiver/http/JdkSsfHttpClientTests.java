package org.easyssf.receiver.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
    void cutsOffResponseLargerThanTheMaximum() throws Exception {
        byte[] body = new byte[1000];
        Arrays.fill(body, (byte) 'x');
        // a response that declares its length is refused before it is read, one without
        // (chunked) is cut off while it is read
        try (OneResponseServer declared = new OneResponseServer("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n", body);
                OneResponseServer chunked = new OneResponseServer(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", chunk(body))) {
            this.httpClient.setMaxResponseSize(999);
            assertThatIOException().isThrownBy(() -> this.httpClient.execute(SsfHttpRequest.get(declared.uri())))
                .withMessageContaining("larger than the 999 bytes accepted");
            assertThatIOException().isThrownBy(() -> this.httpClient.execute(SsfHttpRequest.get(chunked.uri())))
                .withMessageContaining("larger than the 999 bytes accepted");

            this.httpClient.setMaxResponseSize(1000);
            assertThat(this.httpClient.execute(SsfHttpRequest.get(declared.uri())).body())
                .isEqualTo(new String(body, StandardCharsets.UTF_8));
            assertThat(this.httpClient.execute(SsfHttpRequest.get(chunked.uri())).body()).hasSize(1000);
        }
    }

    private static byte[] chunk(byte[] body) {
        byte[] head = (Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] chunked = new byte[head.length + body.length + tail.length];
        System.arraycopy(head, 0, chunked, 0, head.length);
        System.arraycopy(body, 0, chunked, head.length, body.length);
        System.arraycopy(tail, 0, chunked, head.length + body.length, tail.length);
        return chunked;
    }

    /**
     * Answers every request with the same response; the module path of the tests has no
     * {@code jdk.httpserver}.
     */
    private static final class OneResponseServer implements AutoCloseable {

        private final ServerSocket socket;

        private final Thread thread;

        OneResponseServer(String head, byte[] body) throws IOException {
            this.socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.thread = Thread.ofVirtual().start(() -> {
                while (!this.socket.isClosed()) {
                    try (Socket connection = this.socket.accept()) {
                        InputStream in = connection.getInputStream();
                        // the request ends with an empty line
                        int last = 0;
                        for (int c = in.read(); c != -1; c = in.read()) {
                            if (c == '\n' && last == '\n') {
                                break;
                            }
                            if (c != '\r') {
                                last = c;
                            }
                        }
                        OutputStream out = connection.getOutputStream();
                        out.write(head.getBytes(StandardCharsets.US_ASCII));
                        out.write(body);
                        out.flush();
                    }
                    catch (IOException ex) {
                        // closed, or the client went away
                    }
                }
            });
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + this.socket.getLocalPort() + "/");
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
            this.thread.interrupt();
        }

    }

    @Test
    void responseBodiesAreBoundedByDefault() {
        assertThat(this.httpClient.getMaxResponseSize()).isEqualTo(4L * 1024 * 1024);
        assertThatIllegalArgumentException().isThrownBy(() -> this.httpClient.setMaxResponseSize(0));
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

package org.easyssf.receiver.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.easyssf.core.support.SsfAssert;

/**
 * {@link SsfHttpClient} that uses the HTTP client of the JDK. A response body larger than
 * {@link #setMaxResponseSize the maximum} is not buffered: the call fails as if no
 * response was received.
 */
public class JdkSsfHttpClient implements SsfHttpClient {

    private static final String USER_AGENT = "User-Agent";

    private final HttpClient httpClient;

    private final Duration readTimeout;

    private String userAgent;

    private long maxResponseSize = SsfHttpResponse.DEFAULT_MAX_BODY_SIZE;

    public JdkSsfHttpClient() {
        this(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    public JdkSsfHttpClient(Duration connectTimeout, Duration readTimeout) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).build(), readTimeout);
    }

    /**
     * @param httpClient the client to send the requests with
     * @param readTimeout how long to wait for the response to a request
     */
    public JdkSsfHttpClient(HttpClient httpClient, Duration readTimeout) {
        SsfAssert.notNull(httpClient, "httpClient must not be null");
        SsfAssert.notNull(readTimeout, "readTimeout must not be null");
        this.httpClient = httpClient;
        this.readTimeout = readTimeout;
    }

    /**
     * Sets the {@code User-Agent} header to send with every request that does not have
     * one itself.
     * @param userAgent the header value, {@code null} or empty to send the default of the
     * JDK ({@code Java-http-client/<version>})
     */
    public void setUserAgent(String userAgent) {
        this.userAgent = (userAgent != null && !userAgent.isBlank()) ? userAgent : null;
    }

    /**
     * The configured {@code User-Agent} header, {@code null} if the default of the JDK is
     * sent.
     */
    public String getUserAgent() {
        return this.userAgent;
    }

    /**
     * Bounds the response bodies, {@link SsfHttpResponse#DEFAULT_MAX_BODY_SIZE 4 MiB} by
     * default. A transmitter that sends more is treated as unavailable: the receiver
     * never buffers a response of a size the transmitter chooses.
     * @param maxResponseSize the largest response body read, in bytes
     */
    public void setMaxResponseSize(long maxResponseSize) {
        SsfAssert.isTrue(maxResponseSize > 0, "maxResponseSize must be positive");
        this.maxResponseSize = maxResponseSize;
    }

    public long getMaxResponseSize() {
        return this.maxResponseSize;
    }

    @Override
    public SsfHttpResponse execute(SsfHttpRequest request) throws IOException {
        HttpRequest.BodyPublisher body = (request.body() != null) ? HttpRequest.BodyPublishers.ofString(request.body())
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
            .timeout((request.timeout() != null) ? request.timeout() : this.readTimeout)
            .method(request.method(), body);
        request.headers().forEach(builder::header);
        if (this.userAgent != null && request.headers().keySet().stream().noneMatch(USER_AGENT::equalsIgnoreCase)) {
            builder.header(USER_AGENT, this.userAgent);
        }
        try {
            HttpResponse<InputStream> response = this.httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            // closing the stream ends the response early if the body is cut off
            try (InputStream input = response.body()) {
                bytes = SsfHttpResponse.readBody(input,
                        response.headers().firstValueAsLong("Content-Length").orElse(-1), this.maxResponseSize,
                        request.uri());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            return new SsfHttpResponse(response.statusCode(), response.headers().map(),
                    new String(bytes, SsfHttpResponse.charsetOf(contentType)));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while calling " + request.uri());
        }
    }

}

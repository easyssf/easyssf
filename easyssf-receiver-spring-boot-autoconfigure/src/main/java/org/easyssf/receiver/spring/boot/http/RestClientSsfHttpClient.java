package org.easyssf.receiver.spring.boot.http;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link SsfHttpClient} that sends the requests with a {@link RestClient}. Built from the
 * {@code RestClient.Builder} of an application, the calls to the transmitter are
 * configured and instrumented like the other HTTP calls of the application: HTTP client
 * library, TLS, interceptors, metrics and tracing.
 */
public class RestClientSsfHttpClient implements SsfHttpClient {

    private final RestClient restClient;

    public RestClientSsfHttpClient(RestClient restClient) {
        Assert.notNull(restClient, "restClient must not be null");
        this.restClient = restClient;
    }

    @Override
    public SsfHttpResponse execute(SsfHttpRequest request) throws IOException {
        RestClient.RequestBodySpec spec = this.restClient.method(HttpMethod.valueOf(request.method()))
            .uri(request.uri())
            .headers((headers) -> request.headers().forEach(headers::set));
        if (request.body() != null) {
            spec.body(request.body().getBytes(StandardCharsets.UTF_8));
        }
        try {
            // exchange: the response is returned whatever its status
            return spec.exchange((clientRequest, clientResponse) -> {
                Map<String, List<String>> headers = new LinkedHashMap<>();
                clientResponse.getHeaders().forEach(headers::put);
                MediaType contentType = clientResponse.getHeaders().getContentType();
                Charset charset = (contentType != null && contentType.getCharset() != null) ? contentType.getCharset()
                        : StandardCharsets.UTF_8;
                String body = new String(clientResponse.getBody().readAllBytes(), charset);
                return new SsfHttpResponse(clientResponse.getStatusCode().value(), headers, body);
            });
        }
        catch (RestClientException ex) {
            throw new IOException("Request to " + request.uri() + " failed: " + ex.getMessage(), ex);
        }
    }

}

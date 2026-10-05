package org.easyssf.receiver.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP request to the transmitter.
 *
 * @param method the HTTP method
 * @param uri the target of the request
 * @param headers the request headers
 * @param body the request body, {@code null} for none
 * @param timeout how long to wait for the response, {@code null} for the timeout of the
 * client; a long poll (RFC 8936) waits longer than other calls
 */
public record SsfHttpRequest(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {

    public SsfHttpRequest(String method, URI uri, Map<String, String> headers, String body) {
        this(method, uri, headers, body, null);
    }

    public static SsfHttpRequest of(String method, URI uri) {
        return new SsfHttpRequest(method, uri, Map.of(), null, null);
    }

    public static SsfHttpRequest get(URI uri) {
        return of("GET", uri).withHeader("Accept", "application/json");
    }

    public SsfHttpRequest withHeader(String name, String value) {
        Map<String, String> headers = new LinkedHashMap<>(this.headers);
        headers.put(name, value);
        return new SsfHttpRequest(this.method, this.uri, headers, this.body, this.timeout);
    }

    /**
     * @param timeout how long to wait for the response of this request, instead of the
     * timeout of the client
     */
    public SsfHttpRequest withTimeout(Duration timeout) {
        return new SsfHttpRequest(this.method, this.uri, this.headers, this.body, timeout);
    }

    /**
     * Adds an {@code Authorization} header with the given bearer token, if there is one.
     * @param accessToken the access token, may be {@code null}
     */
    public SsfHttpRequest withBearerToken(String accessToken) {
        return (accessToken != null) ? withHeader("Authorization", "Bearer " + accessToken) : this;
    }

    public SsfHttpRequest withBody(String contentType, String body) {
        return new SsfHttpRequest(this.method, this.uri, this.headers, body, this.timeout).withHeader("Content-Type",
                contentType);
    }

    public SsfHttpRequest withJsonBody(String json) {
        return withBody("application/json", json);
    }

}

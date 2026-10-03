package org.easyssf.receiver.http;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP request to the transmitter.
 *
 * @param method the HTTP method
 * @param uri the target of the request
 * @param headers the request headers
 * @param body the request body, {@code null} for none
 */
public record SsfHttpRequest(String method, URI uri, Map<String, String> headers, String body) {

    public static SsfHttpRequest of(String method, URI uri) {
        return new SsfHttpRequest(method, uri, Map.of(), null);
    }

    public static SsfHttpRequest get(URI uri) {
        return of("GET", uri).withHeader("Accept", "application/json");
    }

    public SsfHttpRequest withHeader(String name, String value) {
        Map<String, String> headers = new LinkedHashMap<>(this.headers);
        headers.put(name, value);
        return new SsfHttpRequest(this.method, this.uri, headers, this.body);
    }

    /**
     * Adds an {@code Authorization} header with the given bearer token, if there is one.
     * @param accessToken the access token, may be {@code null}
     */
    public SsfHttpRequest withBearerToken(String accessToken) {
        return (accessToken != null) ? withHeader("Authorization", "Bearer " + accessToken) : this;
    }

    public SsfHttpRequest withBody(String contentType, String body) {
        return new SsfHttpRequest(this.method, this.uri, this.headers, body).withHeader("Content-Type", contentType);
    }

    public SsfHttpRequest withJsonBody(String json) {
        return withBody("application/json", json);
    }

}

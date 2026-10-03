package org.easyssf.receiver.stream;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.easyssf.core.stream.SsfStreamStatus;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.receiver.transmitter.SsfTransmitterTokenProvider;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;

import com.nimbusds.jose.util.JSONArrayUtils;
import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Client for the stream management API of the transmitter (SSF 1.0, section 8.1). The
 * endpoints are discovered from the transmitter metadata.
 */
public class SsfStreamClient {

    private final SsfHttpClient httpClient;

    private final SsfTransmitterTokenProvider tokenProvider;

    private final SsfTransmitterMetadataResolver metadataResolver;

    /**
     * @param httpClient used to call the transmitter
     * @param tokenProvider provides the access token to authenticate with
     * @param metadataResolver resolves the endpoints of the transmitter
     */
    public SsfStreamClient(SsfHttpClient httpClient, SsfTransmitterTokenProvider tokenProvider,
            SsfTransmitterMetadataResolver metadataResolver) {
        SsfAssert.notNull(httpClient, "httpClient must not be null");
        SsfAssert.notNull(tokenProvider, "tokenProvider must not be null");
        SsfAssert.notNull(metadataResolver, "metadataResolver must not be null");
        this.httpClient = httpClient;
        this.tokenProvider = tokenProvider;
        this.metadataResolver = metadataResolver;
    }

    /**
     * The streams of this receiver.
     */
    @SuppressWarnings("unchecked")
    public List<SsfStreamConfiguration> getStreams() {
        String body = exchange("GET", configurationEndpoint(), null);
        List<SsfStreamConfiguration> streams = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return streams;
        }
        try {
            if (body.stripLeading().startsWith("[")) {
                for (Object stream : JSONArrayUtils.parse(body)) {
                    if (stream instanceof Map<?, ?> claims) {
                        streams.add(new SsfStreamConfiguration((Map<String, Object>) claims));
                    }
                }
            }
            else {
                streams.add(new SsfStreamConfiguration(JSONObjectUtils.parse(body)));
            }
        }
        catch (java.text.ParseException ex) {
            throw new SsfStreamException("The transmitter returned an invalid list of streams", 0, ex);
        }
        return streams;
    }

    public SsfStreamConfiguration getStream(String streamId) {
        return new SsfStreamConfiguration(
                parse(exchange("GET", withStreamId(configurationEndpoint(), streamId), null)));
    }

    /**
     * Creates a stream.
     * @throws SsfStreamIssuerMismatchException if the transmitter created the stream with
     * another issuer than its own, the stream must not be used then
     * @see SsfStreamConfiguration#push
     * @see SsfStreamConfiguration#poll
     */
    public SsfStreamConfiguration createStream(SsfStreamConfiguration stream) {
        SsfStreamConfiguration created = new SsfStreamConfiguration(
                parse(exchange("POST", configurationEndpoint(), stream.claims())));
        String issuer = this.metadataResolver.resolve().issuer();
        if (!issuer.equals(created.issuer())) {
            throw new SsfStreamIssuerMismatchException(issuer, created);
        }
        return created;
    }

    /**
     * Updates the given members of a stream, the others are left as they are.
     * @param changes the members to change
     */
    public SsfStreamConfiguration updateStream(String streamId, Map<String, Object> changes) {
        return new SsfStreamConfiguration(
                parse(exchange("PATCH", configurationEndpoint(), withStreamId(changes, streamId))));
    }

    /**
     * Replaces the configuration of a stream.
     */
    public SsfStreamConfiguration replaceStream(String streamId, SsfStreamConfiguration stream) {
        return new SsfStreamConfiguration(
                parse(exchange("PUT", configurationEndpoint(), withStreamId(stream.claims(), streamId))));
    }

    public void deleteStream(String streamId) {
        exchange("DELETE", withStreamId(configurationEndpoint(), streamId), null);
    }

    public SsfStreamStatus getStatus(String streamId) {
        return toStatus(parse(exchange("GET", withStreamId(statusEndpoint(), streamId), null)));
    }

    /**
     * Enables, pauses or disables a stream.
     * @param status one of {@link SsfStreamStatus#ENABLED},
     * {@link SsfStreamStatus#PAUSED} and {@link SsfStreamStatus#DISABLED}
     * @param reason why the status is changed, may be {@code null}
     */
    public SsfStreamStatus updateStatus(String streamId, String status, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (reason != null) {
            body.put("reason", reason);
        }
        return toStatus(parse(exchange("POST", statusEndpoint(), withStreamId(body, streamId))));
    }

    /**
     * Asks the transmitter to send events about the given subject.
     * @param subject a subject identifier, for example
     * {@code Map.of("format", "email", "email", "user@example.com")}
     * @param verified whether the receiver has verified the subject
     */
    public void addSubject(String streamId, Map<String, Object> subject, boolean verified) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("verified", verified);
        exchange("POST", endpoint(SsfTransmitterMetadata::addSubjectEndpoint, "add_subject_endpoint"),
                withStreamId(body, streamId));
    }

    /**
     * Asks the transmitter to no longer send events about the given subject.
     */
    public void removeSubject(String streamId, Map<String, Object> subject) {
        exchange("POST", endpoint(SsfTransmitterMetadata::removeSubjectEndpoint, "remove_subject_endpoint"),
                withStreamId(Map.of("subject", subject), streamId));
    }

    /**
     * Asks the transmitter to send a verification event on the stream.
     * @param state echoed in the verification event, may be {@code null}
     */
    public void requestVerification(String streamId, String state) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (state != null) {
            body.put("state", state);
        }
        exchange("POST", endpoint(SsfTransmitterMetadata::verificationEndpoint, "verification_endpoint"),
                withStreamId(body, streamId));
    }

    private URI configurationEndpoint() {
        return endpoint(SsfTransmitterMetadata::configurationEndpoint, "configuration_endpoint");
    }

    private URI statusEndpoint() {
        return endpoint(SsfTransmitterMetadata::statusEndpoint, "status_endpoint");
    }

    private URI endpoint(Function<SsfTransmitterMetadata, URI> endpoint, String name) {
        SsfTransmitterMetadata metadata;
        try {
            metadata = this.metadataResolver.resolve();
        }
        catch (SsfTransmitterUnavailableException ex) {
            throw new SsfStreamException(ex.getMessage(), 0, ex);
        }
        URI uri = endpoint.apply(metadata);
        if (uri == null) {
            throw new SsfStreamException("The SSF transmitter metadata has no " + name, 0, null);
        }
        return uri;
    }

    private String exchange(String method, URI uri, Map<String, Object> body) {
        SsfHttpResponse response;
        try {
            response = send(method, uri, body);
            if (response.status() == 401) {
                // the access token expired or was revoked: try once more with a new one
                this.tokenProvider.invalidate();
                response = send(method, uri, body);
            }
        }
        catch (IOException | SsfTransmitterUnavailableException ex) {
            throw new SsfStreamException(method + " " + uri + " failed: " + ex.getMessage(), 0, ex);
        }
        if (!response.isSuccessful()) {
            throw new SsfStreamException(
                    method + " " + uri + " failed with status " + response.status() + ": " + response.body(),
                    response.status(), null);
        }
        return response.body();
    }

    private SsfHttpResponse send(String method, URI uri, Map<String, Object> body) throws IOException {
        SsfHttpRequest request = SsfHttpRequest.of(method, uri)
            .withHeader("Accept", "application/json")
            .withBearerToken(this.tokenProvider.getAccessToken());
        if (body != null) {
            request = request.withJsonBody(JSONObjectUtils.toJSONString(body));
        }
        return this.httpClient.execute(request);
    }

    private static Map<String, Object> parse(String body) {
        try {
            return JSONObjectUtils.parse(body);
        }
        catch (Exception ex) {
            throw new SsfStreamException("The transmitter returned an invalid response", 0, ex);
        }
    }

    private static SsfStreamStatus toStatus(Map<String, Object> claims) {
        return new SsfStreamStatus((String) claims.get("stream_id"), (String) claims.get("status"),
                (claims.get("reason") instanceof String reason) ? reason : null);
    }

    private static URI withStreamId(URI endpoint, String streamId) {
        SsfAssert.hasText(streamId, "streamId must not be empty");
        String separator = (endpoint.getRawQuery() != null) ? "&" : "?";
        return URI.create(endpoint + separator + "stream_id=" + URLEncoder.encode(streamId, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> withStreamId(Map<String, Object> body, String streamId) {
        SsfAssert.hasText(streamId, "streamId must not be empty");
        Map<String, Object> withStreamId = new LinkedHashMap<>();
        withStreamId.put("stream_id", streamId);
        withStreamId.putAll(body);
        return withStreamId;
    }

}

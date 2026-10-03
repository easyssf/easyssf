package org.easyssf.receiver.transmitter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * {@link SsfTransmitterTokenProvider} that obtains access tokens with the OAuth2 client
 * credentials grant and caches them until shortly before they expire.
 */
public class ClientCredentialsSsfTransmitterTokenProvider implements SsfTransmitterTokenProvider {

    private static final Duration EXPIRY_SAFETY_WINDOW = Duration.ofSeconds(30);

    private final SsfHttpClient httpClient;

    private final URI tokenUri;

    private final String clientId;

    private final String clientSecret;

    private Collection<String> scopes = List.of();

    private boolean authenticateWithRequestBody;

    private Clock clock = Clock.systemUTC();

    private final ReentrantLock lock = new ReentrantLock();

    private String accessToken;

    private Instant expiresAt = Instant.MIN;

    public ClientCredentialsSsfTransmitterTokenProvider(SsfHttpClient httpClient, URI tokenUri, String clientId,
            String clientSecret) {
        SsfAssert.notNull(httpClient, "httpClient must not be null");
        SsfAssert.notNull(tokenUri, "tokenUri must not be null");
        SsfAssert.hasText(clientId, "clientId must not be empty");
        SsfAssert.hasText(clientSecret, "clientSecret must not be empty");
        this.httpClient = httpClient;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void setScopes(Collection<String> scopes) {
        this.scopes = (scopes != null) ? List.copyOf(scopes) : List.of();
    }

    /**
     * Whether the client credentials are sent in the request body
     * ({@code client_secret_post}) instead of with HTTP basic authentication
     * ({@code client_secret_basic}).
     */
    public void setAuthenticateWithRequestBody(boolean authenticateWithRequestBody) {
        this.authenticateWithRequestBody = authenticateWithRequestBody;
    }

    public void setClock(Clock clock) {
        SsfAssert.notNull(clock, "clock must not be null");
        this.clock = clock;
    }

    @Override
    public void invalidate() {
        this.lock.lock();
        try {
            this.accessToken = null;
        }
        finally {
            this.lock.unlock();
        }
    }

    @Override
    public String getAccessToken() {
        // a lock, not a monitor: the token request blocks, and a virtual thread waiting
        // for it must not pin its carrier (Java 21 to 23)
        this.lock.lock();
        try {
            if (this.accessToken == null || !this.clock.instant().isBefore(this.expiresAt)) {
                requestToken();
            }
            return this.accessToken;
        }
        finally {
            this.lock.unlock();
        }
    }

    private void requestToken() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (!this.scopes.isEmpty()) {
            form.put("scope", String.join(" ", this.scopes));
        }
        SsfHttpRequest request = SsfHttpRequest.of("POST", this.tokenUri).withHeader("Accept", "application/json");
        if (this.authenticateWithRequestBody) {
            form.put("client_id", this.clientId);
            form.put("client_secret", this.clientSecret);
        }
        else {
            String credentials = this.clientId + ":" + this.clientSecret;
            request = request.withHeader("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        String body = form.entrySet()
            .stream()
            .map((parameter) -> parameter.getKey() + "="
                    + URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        Map<String, Object> response;
        try {
            SsfHttpResponse httpResponse = this.httpClient
                .execute(request.withBody("application/x-www-form-urlencoded", body));
            if (!httpResponse.isSuccessful()) {
                throw new IllegalStateException("The token endpoint answered with status " + httpResponse.status());
            }
            response = JSONObjectUtils.parse(httpResponse.body());
        }
        catch (Exception ex) {
            throw new SsfTransmitterUnavailableException(
                    "Could not obtain an access token for the SSF transmitter from " + this.tokenUri, ex);
        }
        if (!(response.get("access_token") instanceof String token) || token.isBlank()) {
            throw new SsfTransmitterUnavailableException(
                    "The token response of " + this.tokenUri + " has no access_token", null);
        }
        long expiresIn = (response.get("expires_in") instanceof Number seconds) ? seconds.longValue() : 60;
        this.accessToken = token;
        // renew ahead of the expiry, but still use a short-lived token for most of its
        // lifetime
        Duration safetyWindow = Duration.ofSeconds(Math.min(EXPIRY_SAFETY_WINDOW.toSeconds(), expiresIn / 4));
        this.expiresAt = this.clock.instant().plusSeconds(expiresIn).minus(safetyWindow);
    }

}

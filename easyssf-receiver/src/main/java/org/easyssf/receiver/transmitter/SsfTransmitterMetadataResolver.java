package org.easyssf.receiver.transmitter;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Resolves the {@link SsfTransmitterMetadata} of the transmitter. The metadata is fetched
 * on first use and cached once it was retrieved successfully, so a receiver can start
 * even when the transmitter is not reachable.
 */
public class SsfTransmitterMetadataResolver {

    private static final Logger logger = LoggerFactory.getLogger(SsfTransmitterMetadataResolver.class);

    private static final String WELL_KNOWN_PATH = "/.well-known/ssf-configuration";

    private final String issuer;

    private final List<URI> metadataUris;

    private final SsfHttpClient httpClient;

    private final SsfTransmitterUriPolicy uriPolicy;

    private volatile boolean warnedInsecure;

    private volatile SsfTransmitterMetadata metadata;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param issuer the transmitter issuer
     * @param metadataUri explicit metadata location, or {@code null} to derive it from
     * the issuer
     * @param httpClient used to fetch the metadata
     */
    public SsfTransmitterMetadataResolver(String issuer, URI metadataUri, SsfHttpClient httpClient) {
        this(issuer, metadataUri, httpClient, SsfTransmitterUriPolicy.DEFAULT);
    }

    /**
     * @param issuer the issuer of the transmitter
     * @param metadataUri where the metadata is, {@code null} to derive it from the issuer
     * @param httpClient retrieves the metadata
     * @param uriPolicy what the issuer and the endpoints of the transmitter must look
     * like
     * @throws IllegalArgumentException if the issuer or the metadata URI is not
     * acceptable
     */
    public SsfTransmitterMetadataResolver(String issuer, URI metadataUri, SsfHttpClient httpClient,
            SsfTransmitterUriPolicy uriPolicy) {
        SsfAssert.hasText(issuer, "issuer must not be empty");
        SsfAssert.notNull(httpClient, "httpClient must not be null");
        SsfAssert.notNull(uriPolicy, "uriPolicy must not be null");
        uriPolicy.checkIssuer(issuer);
        if (metadataUri != null) {
            uriPolicy.checkEndpoint(metadataUri, "Transmitter metadata URL");
        }
        this.issuer = issuer;
        this.metadataUris = (metadataUri != null) ? List.of(metadataUri) : deriveMetadataUris(issuer);
        this.httpClient = httpClient;
        this.uriPolicy = uriPolicy;
    }

    /**
     * The locations the metadata is looked up at, in order.
     */
    public String getIssuer() {
        return this.issuer;
    }

    public List<URI> getMetadataUris() {
        return this.metadataUris;
    }

    /**
     * @return the metadata if it was retrieved already, without retrieving it
     */
    public Optional<SsfTransmitterMetadata> getResolvedMetadata() {
        return Optional.ofNullable(this.metadata);
    }

    /**
     * @throws SsfTransmitterUnavailableException if the metadata cannot be retrieved
     */
    public SsfTransmitterMetadata resolve() {
        SsfTransmitterMetadata metadata = this.metadata;
        if (metadata == null) {
            // a lock, not a monitor: the fetch blocks, and a virtual thread waiting for
            // it
            // must not pin its carrier (Java 21 to 23)
            this.lock.lock();
            try {
                metadata = this.metadata;
                if (metadata == null) {
                    metadata = fetch();
                    this.metadata = metadata;
                }
            }
            finally {
                this.lock.unlock();
            }
        }
        return metadata;
    }

    private SsfTransmitterMetadata fetch() {
        Exception failure = null;
        for (URI metadataUri : this.metadataUris) {
            try {
                SsfTransmitterMetadata metadata = fetch(metadataUri);
                logger.info("Resolved SSF transmitter metadata from " + metadataUri);
                return metadata;
            }
            catch (Exception ex) {
                logger.debug("Could not resolve SSF transmitter metadata from " + metadataUri, ex);
                if (failure == null) {
                    failure = ex;
                }
                else {
                    failure.addSuppressed(ex);
                }
            }
        }
        throw new SsfTransmitterUnavailableException(
                "Could not resolve SSF transmitter metadata from " + this.metadataUris, failure);
    }

    private SsfTransmitterMetadata fetch(URI metadataUri) throws Exception {
        SsfHttpResponse response = this.httpClient.execute(SsfHttpRequest.get(metadataUri));
        if (!response.isSuccessful()) {
            throw new IllegalStateException("The transmitter answered with status " + response.status());
        }
        Map<String, Object> claims = JSONObjectUtils.parse(response.body());
        Object metadataIssuer = claims.get("issuer");
        if (!this.issuer.equals(metadataIssuer)) {
            throw new IllegalStateException("SSF transmitter metadata issuer '" + metadataIssuer
                    + "' does not match the configured transmitter issuer '" + this.issuer + "'");
        }
        URI jwksUri = (claims.get("jwks_uri") instanceof String value) ? URI.create(value) : null;
        SsfTransmitterMetadata metadata = new SsfTransmitterMetadata(this.issuer, jwksUri, claims);
        checkEndpoints(metadata);
        return metadata;
    }

    /**
     * The metadata controls where keys and stream operations come from, so its endpoints
     * are held to the same standard as the issuer.
     */
    private void checkEndpoints(SsfTransmitterMetadata metadata) {
        List<String> insecure = new ArrayList<>();
        check(metadata.jwksUri(), "jwks_uri", insecure);
        check(metadata.configurationEndpoint(), "configuration_endpoint", insecure);
        check(metadata.statusEndpoint(), "status_endpoint", insecure);
        check(metadata.addSubjectEndpoint(), "add_subject_endpoint", insecure);
        check(metadata.removeSubjectEndpoint(), "remove_subject_endpoint", insecure);
        check(metadata.verificationEndpoint(), "verification_endpoint", insecure);
        if (!insecure.isEmpty() && this.uriPolicy.allowInsecureHttp() && !this.warnedInsecure) {
            this.warnedInsecure = true;
            logger.warn("The SSF transmitter " + this.issuer + " publishes endpoints without TLS, accepted because "
                    + "insecure http is allowed. For development only: " + insecure);
        }
    }

    private void check(URI endpoint, String name, List<String> insecure) {
        if (endpoint != null) {
            this.uriPolicy.checkEndpoint(endpoint, "SSF transmitter metadata " + name);
            if (SsfTransmitterUriPolicy.isInsecure(endpoint) && !SsfTransmitterUriPolicy.isLoopback(endpoint)) {
                insecure.add(name + " " + endpoint);
            }
        }
    }

    /**
     * Derives the metadata locations for an issuer. SSF 1.0 (section 7.2) inserts
     * {@code /.well-known/ssf-configuration} between the host and the path of the issuer.
     * Transmitters that grew out of OpenID providers tend to append it to the issuer
     * instead, so that location is used as a fallback.
     */
    static List<URI> deriveMetadataUris(String issuer) {
        URI uri = URI.create(issuer);
        SsfAssert.isTrue(uri.getScheme() != null && uri.getRawAuthority() != null,
                "Transmitter issuer must be an absolute URI: " + issuer);
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = (uri.getRawPath() != null) ? uri.getRawPath() : "";
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        Set<URI> uris = new LinkedHashSet<>();
        uris.add(URI.create(base + WELL_KNOWN_PATH + path));
        uris.add(URI.create(base + path + WELL_KNOWN_PATH));
        return List.copyOf(uris);
    }

}

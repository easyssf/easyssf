package org.easyssf.core.metadata;

import java.net.URI;
import java.util.Map;

/**
 * The transmitter configuration metadata published at
 * {@code .well-known/ssf-configuration} (SSF 1.0, section 7.1).
 *
 * @param issuer the transmitter issuer
 * @param jwksUri location of the transmitter's JWK Set, may be {@code null}
 * @param claims all members of the metadata document
 */
public record SsfTransmitterMetadata(String issuer, URI jwksUri, Map<String, Object> claims) {

    /**
     * Endpoint to create, read, update and delete streams, may be {@code null}.
     */
    public URI configurationEndpoint() {
        return uri("configuration_endpoint");
    }

    /**
     * Endpoint to read and update the status of a stream, may be {@code null}.
     */
    public URI statusEndpoint() {
        return uri("status_endpoint");
    }

    /**
     * Endpoint to add subjects to a stream, may be {@code null}.
     */
    public URI addSubjectEndpoint() {
        return uri("add_subject_endpoint");
    }

    /**
     * Endpoint to remove subjects from a stream, may be {@code null}.
     */
    public URI removeSubjectEndpoint() {
        return uri("remove_subject_endpoint");
    }

    /**
     * Endpoint to request a verification event, may be {@code null}.
     */
    public URI verificationEndpoint() {
        return uri("verification_endpoint");
    }

    private URI uri(String name) {
        return (this.claims.get(name) instanceof String value && !value.isBlank()) ? URI.create(value) : null;
    }

}

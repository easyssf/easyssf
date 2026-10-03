package org.easyssf.core.metadata;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.core.support.SsfCollections;

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
     * The {@code spec_version} of the final SSF 1.0 specification.
     */
    public static final String SPEC_VERSION_1_0 = "1_0";

    public SsfTransmitterMetadata {
        claims = SsfCollections.copyOf((claims != null) ? claims : Map.of());
    }

    /**
     * @return the version of the specification the transmitter implements
     * ({@code spec_version}), {@code null} if the transmitter does not say
     */
    public String specVersion() {
        return string("spec_version");
    }

    /**
     * @return the delivery method URIs the transmitter supports
     * ({@code delivery_methods_supported}), empty if the transmitter does not say
     */
    public List<String> deliveryMethodsSupported() {
        return strings("delivery_methods_supported");
    }

    /**
     * @return whether the transmitter supports the delivery method; {@code true} as well
     * if it does not say which methods it supports
     */
    public boolean supportsDeliveryMethod(SsfDeliveryMethod method) {
        List<String> supported = deliveryMethodsSupported();
        return supported.isEmpty() || supported.contains(method.uri());
    }

    /**
     * @return the members of a complex subject the transmitter requires to be understood
     * ({@code critical_subject_members}), empty if none
     */
    public List<String> criticalSubjectMembers() {
        return strings("critical_subject_members");
    }

    /**
     * @return the authorization schemes of the management endpoints
     * ({@code authorization_schemes}), each with a {@code spec_urn}, empty if the
     * transmitter does not say
     */
    public List<Map<String, Object>> authorizationSchemes() {
        if (!(this.claims.get("authorization_schemes") instanceof List<?> schemes)) {
            return List.of();
        }
        return schemes.stream().filter(Map.class::isInstance).map(SsfTransmitterMetadata::asMap).toList();
    }

    /**
     * @return which subjects a stream receives events for by default
     * ({@code default_subjects}): {@code ALL} or {@code NONE}, {@code null} if the
     * transmitter does not say
     */
    public String defaultSubjects() {
        return string("default_subjects");
    }

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

    private String string(String name) {
        return (this.claims.get(name) instanceof String value && !value.isBlank()) ? value : null;
    }

    private List<String> strings(String name) {
        if (!(this.claims.get(name) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

}

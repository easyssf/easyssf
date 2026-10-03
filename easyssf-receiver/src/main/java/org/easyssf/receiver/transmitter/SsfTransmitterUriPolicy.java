package org.easyssf.receiver.transmitter;

import java.net.URI;

/**
 * What the issuer of a transmitter and the endpoints it publishes must look like. SSF
 * requires {@code https} and an issuer without query or fragment. Loopback addresses
 * ({@code localhost}, {@code 127.0.0.1}, {@code ::1}) may use {@code http}, so that a
 * transmitter on the developer's machine works; {@link #allowInsecureHttp()} extends that
 * to every host, for test setups only.
 *
 * @param allowInsecureHttp whether {@code http} is accepted for hosts other than loopback
 */
public record SsfTransmitterUriPolicy(boolean allowInsecureHttp) {

    /**
     * {@code https}, or {@code http} on loopback addresses only.
     */
    public static final SsfTransmitterUriPolicy DEFAULT = new SsfTransmitterUriPolicy(false);

    /**
     * {@code http} on any host as well. For development and test setups only: the
     * metadata of the transmitter decides where keys come from, and without TLS anyone on
     * the path can change it.
     */
    public static final SsfTransmitterUriPolicy INSECURE = new SsfTransmitterUriPolicy(true);

    /**
     * @return whether the URI uses plain {@code http}
     */
    public static boolean isInsecure(URI uri) {
        return uri != null && uri.getScheme() != null && "http".equalsIgnoreCase(uri.getScheme());
    }

    /**
     * @param issuer the issuer of the transmitter
     * @return the issuer as a URI
     * @throws IllegalArgumentException if the issuer is not acceptable
     */
    public URI checkIssuer(String issuer) {
        URI uri = parse(issuer, "Transmitter issuer");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Transmitter issuer must not have a query or fragment: " + issuer);
        }
        checkScheme(uri, "Transmitter issuer");
        return uri;
    }

    /**
     * @param uri an endpoint of the transmitter: its metadata, JWK Set, stream management
     * or poll endpoint
     * @param description what the endpoint is, for the message of the exception
     * @throws IllegalArgumentException if the endpoint is not acceptable
     */
    public void checkEndpoint(URI uri, String description) {
        if (uri == null) {
            throw new IllegalArgumentException(description + " must not be null");
        }
        parse(uri.toString(), description);
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException(description + " must not have a fragment: " + uri);
        }
        checkScheme(uri, description);
    }

    private URI parse(String value, String description) {
        URI uri;
        try {
            uri = URI.create(value);
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(description + " is not a valid URI: " + value, ex);
        }
        if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(description + " must be an absolute URI with a host: " + value);
        }
        return uri;
    }

    private void checkScheme(URI uri, String description) {
        String scheme = uri.getScheme().toLowerCase();
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && (this.allowInsecureHttp || isLoopback(uri))) {
            return;
        }
        throw new IllegalArgumentException(description + " must use https: " + uri);
    }

    /**
     * @return whether the host of the URI is a loopback address
     */
    public static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.")
                || host.equals("[::1]") || host.equals("::1") || host.equals("[0:0:0:0:0:0:0:1]");
    }

}

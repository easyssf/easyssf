package org.easyssf.receiver.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The response of the transmitter to an {@link SsfHttpRequest}.
 *
 * @param status the HTTP status
 * @param headers the response headers
 * @param body the response body, empty if there was none
 */
public record SsfHttpResponse(int status, Map<String, List<String>> headers, String body) {

    /**
     * How large a response body may be unless an {@link SsfHttpClient} is configured
     * otherwise, 4 MiB. The receiver reads transmitter metadata, JWK Sets, stream
     * configurations and poll responses, none of which comes near that, so a larger
     * response is a fault or an attack and is cut off rather than buffered.
     */
    public static final long DEFAULT_MAX_BODY_SIZE = 4L * 1024 * 1024;

    /**
     * Reads a response body of at most {@code maxSize} bytes, for implementations of
     * {@link SsfHttpClient}.
     * @param body the body as the HTTP client delivers it
     * @param declaredLength the {@code Content-Length} the response declares, {@code -1}
     * if it declares none
     * @param maxSize the largest body accepted, in bytes
     * @param uri the URI the response came from, for the message of the exception
     * @return the body
     * @throws IOException if the body is larger than {@code maxSize}, or cannot be read
     */
    public static byte[] readBody(InputStream body, long declaredLength, long maxSize, URI uri) throws IOException {
        if (declaredLength > maxSize) {
            throw new IOException("The response of " + uri + " is " + declaredLength + " bytes, larger than the "
                    + maxSize + " bytes accepted");
        }
        int limit = (int) Math.min(maxSize, Integer.MAX_VALUE - 16);
        byte[] bytes = body.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new IOException(
                    "The response of " + uri + " is larger than the " + maxSize + " bytes accepted and was cut off");
        }
        return bytes;
    }

    /**
     * @param contentType the {@code Content-Type} header of a response, may be
     * {@code null}
     * @return the charset the header names, UTF-8 if it names none or an unknown one
     */
    public static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String parameter : contentType.split(";")) {
            String trimmed = parameter.strip();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = trimmed.substring(8).strip();
                if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                try {
                    return Charset.forName(name);
                }
                catch (IllegalArgumentException ex) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    public boolean isSuccessful() {
        return this.status >= 200 && this.status < 300;
    }

    /**
     * The first value of the header with the given name, ignoring its case.
     * @return the value, {@code null} if the response has no such header
     */
    public String header(String name) {
        for (Map.Entry<String, List<String>> header : this.headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
                return header.getValue().get(0);
            }
        }
        return null;
    }

}

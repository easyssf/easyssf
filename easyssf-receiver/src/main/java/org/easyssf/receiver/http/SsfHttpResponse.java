package org.easyssf.receiver.http;

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

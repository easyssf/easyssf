package org.easyssf.receiver.push;

/**
 * The response to a push request (RFC 8935, section 2.2 to 2.4).
 *
 * @param status the HTTP status
 * @param body the JSON error document, {@code null} if the response has no body; send it
 * with the {@link #CONTENT_TYPE} and {@link #CONTENT_LANGUAGE} headers
 */
public record SsfPushResponse(int status, String body) {

    /**
     * The content type of {@link #body()}.
     */
    public static final String CONTENT_TYPE = "application/json";

    /**
     * The language of the description in {@link #body()}, to be sent as
     * {@code Content-Language} header (RFC 8935, section 2.3).
     */
    public static final String CONTENT_LANGUAGE = "en";

    /**
     * Whether the SET was accepted.
     */
    public boolean isAccepted() {
        return this.status == 202;
    }

}

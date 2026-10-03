package org.easyssf.core.stream;

/**
 * The status of an event stream (SSF 1.0, section 8.1.2).
 *
 * @param streamId the identifier of the stream
 * @param status {@code enabled}, {@code paused} or {@code disabled}
 * @param reason why the stream has that status, may be {@code null}
 */
public record SsfStreamStatus(String streamId, String status, String reason) {

    public static final String ENABLED = "enabled";

    public static final String PAUSED = "paused";

    public static final String DISABLED = "disabled";

}

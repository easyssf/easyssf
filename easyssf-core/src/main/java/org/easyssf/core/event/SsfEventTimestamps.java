package org.easyssf.core.event;

import java.time.Instant;

/**
 * Reads the {@code event_timestamp} claim of an event payload. CAEP 1.0 (section 2) and
 * RISC 1.0 (section 2.7) define it as a JSON number of seconds since the epoch.
 */
public final class SsfEventTimestamps {

    private SsfEventTimestamps() {
    }

    /**
     * @param value the value of an {@code event_timestamp} claim
     * @return the instant, {@code null} if the value is not a number. A value that is
     * clearly milliseconds is read as such: some transmitters send milliseconds.
     */
    public static Instant from(Object value) {
        if (!(value instanceof Number timestamp)) {
            return null;
        }
        long number = timestamp.longValue();
        // CAEP defines seconds since epoch, some transmitters send milliseconds
        return (number > 100_000_000_000L) ? Instant.ofEpochMilli(number) : Instant.ofEpochSecond(number);
    }

}

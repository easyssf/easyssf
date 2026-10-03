package org.easyssf.receiver.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfEventContextTests {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-02T10:00:00Z");

    @Test
    void findsEventsByAliasAndUri() {
        SsfEventContext context = context(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("reason", "admin")), null);
        assertThat(context.hasEvent("CaepSessionRevoked")).isTrue();
        assertThat(context.hasEvent(SsfEventTypes.CAEP_SESSION_REVOKED)).isTrue();
        assertThat(context.hasEvent("CaepCredentialChange")).isFalse();
        assertThat(context.eventFor("CaepSessionRevoked")).containsEntry("reason", "admin");
        assertThat(context.eventFor("CaepCredentialChange")).isNull();
        assertThat(context.eventTypes()).containsExactly(SsfEventTypes.CAEP_SESSION_REVOKED);
    }

    @Test
    void vendorEventTypesAreAddressedByUri() {
        String vendorType = "https://schemas.example.org/event-type/widget-replaced";
        SsfEventContext context = context(Map.of(vendorType, Map.of()), null);
        assertThat(context.hasEvent(vendorType)).isTrue();
        assertThat(SsfEventTypes.aliasOf(vendorType)).isEqualTo(vendorType);
    }

    @Test
    void subjectIsTakenFromSubId() {
        SsfEventContext context = context(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of()), opaque("abc"));
        assertThat(context.subjectFor("CaepSessionRevoked").opaqueId()).isEqualTo("abc");
    }

    @Test
    void subjectFallsBackToEventSubjectOfEarlyDrafts() {
        Map<String, Object> event = Map.of("subject", Map.of("session", opaque("session-1")));
        SsfEventContext context = context(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, event), null);
        assertThat(context.subject().isEmpty()).isTrue();
        assertThat(context.subjectFor("CaepSessionRevoked").sessionId()).isEqualTo("session-1");
    }

    @Test
    void eventTimestampInSecondsOrMillisecondsElseIssuedAt() {
        Instant occurred = Instant.parse("2026-10-02T09:59:00Z");
        assertThat(timestamp(Map.of("event_timestamp", occurred.getEpochSecond()))).isEqualTo(occurred);
        assertThat(timestamp(Map.of("event_timestamp", occurred.toEpochMilli()))).isEqualTo(occurred);
        assertThat(timestamp(Map.of())).isEqualTo(ISSUED_AT);
    }

    private static Instant timestamp(Map<String, Object> event) {
        return context(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, event), null).eventTimestamp("CaepSessionRevoked");
    }

    private static SsfEventContext context(Map<String, Object> events, Map<String, Object> subjectId) {
        return new SsfEventContext(new SsfEventToken("jti-1", "https://idp.example", ISSUED_AT, List.of(), events,
                subjectId, null, Map.of()));
    }

}

package org.easyssf.core.risc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;

class SsfRiscEventTests {

    private static final String UNKNOWN_RISC_EVENT = "https://schemas.openid.net/secevent/risc/event-type/account-merged";

    @Test
    void everyRiscEventTypeHasAKindAndAnAlias() {
        for (SsfRiscEventKind kind : SsfRiscEventKind.values()) {
            if (kind == SsfRiscEventKind.OTHER) {
                assertThat(kind.eventType()).isNull();
                continue;
            }
            assertThat(kind.eventType()).startsWith("https://schemas.openid.net/secevent/risc/event-type/");
            assertThat(SsfRiscEventKind.of(kind.eventType())).isEqualTo(kind);
            assertThat(SsfEventTypes.aliasOf(kind.eventType())).startsWith("Risc");
            assertThat(SsfRiscEventKind.of(SsfEventTypes.aliasOf(kind.eventType()))).isEqualTo(kind);
        }
        assertThat(SsfRiscEventKind.values()).hasSize(15);
        assertThat(SsfRiscEventKind.of("RiscSessionsRevoked")).isEqualTo(SsfRiscEventKind.SESSIONS_REVOKED);
    }

    @Test
    void unknownEventTypeInTheRiscNamespaceIsOtherAndAnythingElseIsNotRisc() {
        assertThat(SsfRiscEventKind.of(UNKNOWN_RISC_EVENT)).isEqualTo(SsfRiscEventKind.OTHER);
        assertThat(SsfRiscEventKind.of(SsfEventTypes.CAEP_SESSION_REVOKED)).isNull();
        assertThat(SsfRiscEventKind.of("ScimProvDelete")).isNull();
        assertThat(SsfRiscEventKind.of(null)).isNull();
    }

    @Test
    void optInAndOptOutEventsReportTheParticipation() {
        assertThat(SsfRiscEventKind.values()).filteredOn(SsfRiscEventKind::isOptOutState)
            .containsExactly(SsfRiscEventKind.OPT_IN, SsfRiscEventKind.OPT_OUT_INITIATED,
                    SsfRiscEventKind.OPT_OUT_CANCELLED, SsfRiscEventKind.OPT_OUT_EFFECTIVE);
    }

    @Test
    void accountDisabledMayCarryAReason() {
        SsfRiscEvent event = single(SsfEventTypes.RISC_ACCOUNT_DISABLED, Map.of("reason", "hijacking"));
        assertThat(event.kind()).isEqualTo(SsfRiscEventKind.ACCOUNT_DISABLED);
        assertThat(event.reason()).isEqualTo("hijacking");
        assertThat(single(SsfEventTypes.RISC_ACCOUNT_DISABLED, Map.of()).reason()).isNull();
    }

    @Test
    void identifierChangedMayCarryTheNewValue() {
        SsfRiscEvent event = single(SsfEventTypes.RISC_IDENTIFIER_CHANGED, Map.of("new-value", "john.roe@example.com"));
        assertThat(event.kind()).isEqualTo(SsfRiscEventKind.IDENTIFIER_CHANGED);
        assertThat(event.newValue()).isEqualTo("john.roe@example.com");
        assertThat(single(SsfEventTypes.RISC_IDENTIFIER_RECYCLED, Map.of()).newValue()).isNull();
    }

    @Test
    void credentialCompromiseNamesTheCredentialAndWhenItWasFound() {
        SsfRiscEvent event = single(SsfEventTypes.RISC_CREDENTIAL_COMPROMISE,
                Map.of("credential_type", "password", "event_timestamp", 1508184845, "reason_admin",
                        "Found in a breach dump", "reason_user",
                        Map.of("en", "Your password was found in a data breach")));
        assertThat(event.kind()).isEqualTo(SsfRiscEventKind.CREDENTIAL_COMPROMISE);
        assertThat(event.credentialType()).isEqualTo("password");
        assertThat(event.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1508184845));
        assertThat(event.reasonAdmin()).isEqualTo("Found in a breach dump");
        assertThat(event.reasonUser()).contains("Your password was found in a data breach");
        SsfRiscEvent minimal = single(SsfEventTypes.RISC_CREDENTIAL_COMPROMISE, Map.of("credential_type", "password"));
        assertThat(minimal.eventTimestamp()).isNull();
        assertThat(minimal.reasonAdmin()).isNull();
        assertThat(minimal.reasonUser()).isNull();
    }

    @Test
    void eventsWithoutAttributesHaveAnEmptyPayload() {
        assertThat(single(SsfEventTypes.RISC_ACCOUNT_PURGED, Map.of()).payload()).isEmpty();
        assertThat(single(SsfEventTypes.RISC_OPT_OUT_EFFECTIVE, null).payload()).isEmpty();
    }

    @Test
    void findsAllRiscEventsOfASetInOrderAndIgnoresOthers() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of());
        events.put(SsfEventTypes.RISC_ACCOUNT_DISABLED, Map.of("reason", "bulk-account"));
        events.put(UNKNOWN_RISC_EVENT, Map.of("into", "other-account"));
        List<SsfRiscEvent> riscEvents = SsfRiscEvent.in(token(events));
        assertThat(riscEvents).extracting(SsfRiscEvent::kind)
            .containsExactly(SsfRiscEventKind.ACCOUNT_DISABLED, SsfRiscEventKind.OTHER);
        assertThat(riscEvents.get(1).eventType()).isEqualTo(UNKNOWN_RISC_EVENT);
        assertThat(riscEvents.get(1).payload()).containsEntry("into", "other-account");
        assertThat(SsfRiscEvent.in(token(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of())))).isEmpty();
    }

    @Test
    void ofResolvesAliasesAndRejectsOtherEventTypes() {
        SsfRiscEvent event = SsfRiscEvent.of("RiscAccountPurged", null);
        assertThat(event.eventType()).isEqualTo(SsfEventTypes.RISC_ACCOUNT_PURGED);
        assertThat(event.kind()).isEqualTo(SsfRiscEventKind.ACCOUNT_PURGED);
        assertThat(SsfRiscEvent.of("CaepSessionRevoked", Map.of())).isNull();
    }

    private static SsfRiscEvent single(String eventType, Map<String, Object> payload) {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(eventType, payload);
        List<SsfRiscEvent> riscEvents = SsfRiscEvent.in(token(events));
        assertThat(riscEvents).hasSize(1);
        return riscEvents.get(0);
    }

    private static SsfEventToken token(Map<String, Object> events) {
        return new SsfEventToken("jti-1", "https://idp.example.com", Instant.parse("2026-10-06T10:00:00Z"), List.of(),
                events, email("john.doe@example.com"), null, Map.of());
    }

}

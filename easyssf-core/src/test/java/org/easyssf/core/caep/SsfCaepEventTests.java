package org.easyssf.core.caep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;

class SsfCaepEventTests {

    private static final String UNKNOWN_CAEP_EVENT = "https://schemas.openid.net/secevent/caep/event-type/geo-fence-exit";

    @Test
    void everyCaepEventTypeHasAKindAndAnAlias() {
        for (SsfCaepEventKind kind : SsfCaepEventKind.values()) {
            if (kind == SsfCaepEventKind.OTHER) {
                assertThat(kind.eventType()).isNull();
                continue;
            }
            assertThat(kind.eventType()).startsWith("https://schemas.openid.net/secevent/caep/event-type/");
            assertThat(SsfCaepEventKind.of(kind.eventType())).isEqualTo(kind);
            assertThat(SsfEventTypes.aliasOf(kind.eventType())).startsWith("Caep");
            assertThat(SsfCaepEventKind.of(SsfEventTypes.aliasOf(kind.eventType()))).isEqualTo(kind);
        }
        assertThat(SsfCaepEventKind.values()).hasSize(9);
    }

    @Test
    void unknownEventTypeInTheCaepNamespaceIsOtherAndAnythingElseIsNotCaep() {
        assertThat(SsfCaepEventKind.of(UNKNOWN_CAEP_EVENT)).isEqualTo(SsfCaepEventKind.OTHER);
        assertThat(SsfCaepEventKind.of(SsfEventTypes.RISC_ACCOUNT_DISABLED)).isNull();
        assertThat(SsfCaepEventKind.of("ScimProvDelete")).isNull();
        assertThat(SsfCaepEventKind.of("urn:example:acme:login")).isNull();
        assertThat(SsfCaepEventKind.of(null)).isNull();
    }

    @Test
    void commonClaimsOfSection2AreTypedForEveryKind() {
        Map<String, Object> payload = Map.of("event_timestamp", 1615304991, "initiating_entity", "policy",
                "reason_admin", Map.of("en", "Landspeed Policy Violation: C076E82F"), "reason_user", Map.of("en",
                        "Access attempt from multiple regions.", "es-410", "Intento de acceso desde varias regiones."));
        SsfCaepEvent event = single(SsfEventTypes.CAEP_SESSION_REVOKED, payload);
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.SESSION_REVOKED);
        assertThat(event.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1615304991));
        assertThat(event.initiatingEntity()).isEqualTo("policy");
        assertThat(event.reasonAdmin()).containsExactly(Map.entry("en", "Landspeed Policy Violation: C076E82F"));
        assertThat(event.reasonUser()).containsOnlyKeys("en", "es-410");
        // the same claims on an event this version does not know
        SsfCaepEvent other = single(UNKNOWN_CAEP_EVENT, payload);
        assertThat(other.kind()).isEqualTo(SsfCaepEventKind.OTHER);
        assertThat(other.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1615304991));
        assertThat(other.initiatingEntity()).isEqualTo("policy");
        assertThat(other.reasonUser()).containsKey("es-410");
        assertThat(other.payload()).isEqualTo(payload);
    }

    @Test
    void absentCommonClaimsAreNullOrEmptyAndMillisecondsAreTolerated() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of());
        assertThat(event.eventTimestamp()).isNull();
        assertThat(event.initiatingEntity()).isNull();
        assertThat(event.reasonAdmin()).isEmpty();
        assertThat(event.reasonUser()).isEmpty();
        assertThat(
                single(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("event_timestamp", 1615304991000L)).eventTimestamp())
            .isEqualTo(Instant.ofEpochSecond(1615304991));
        assertThat(single(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("event_timestamp", "yesterday")).eventTimestamp())
            .isNull();
    }

    @Test
    void plainStringReasonIsKeptUnderTheEmptyLanguageTag() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("reason_admin", "Policy violation"));
        assertThat(event.reasonAdmin()).containsExactly(Map.entry("", "Policy violation"));
    }

    @Test
    void tokenClaimsChangeCarriesTheNewClaims() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_TOKEN_CLAIMS_CHANGE,
                Map.of("event_timestamp", 1615304991, "claims", Map.of("role", "ro-admin")));
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.TOKEN_CLAIMS_CHANGE);
        assertThat(event.claims()).containsExactly(Map.entry("role", "ro-admin"));
        assertThat(single(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of()).claims()).isNull();
    }

    @Test
    void credentialChangeNamesTheCredential() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_CREDENTIAL_CHANGE,
                Map.of("credential_type", "fido2-roaming", "change_type", "create", "fido2_aaguid",
                        "accced6a-63f5-490a-9eea-e59bc1896cfc", "friendly_name", "Jane's USB authenticator",
                        "initiating_entity", "user", "reason_admin", Map.of("en", "User self-enrollment")));
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.CREDENTIAL_CHANGE);
        assertThat(event.credentialType()).isEqualTo("fido2-roaming");
        assertThat(event.changeType()).isEqualTo("create");
        assertThat(event.fido2Aaguid()).isEqualTo("accced6a-63f5-490a-9eea-e59bc1896cfc");
        assertThat(event.friendlyName()).isEqualTo("Jane's USB authenticator");
        assertThat(event.x509Issuer()).isNull();
        assertThat(event.x509Serial()).isNull();
        SsfCaepEvent x509 = single(SsfEventTypes.CAEP_CREDENTIAL_CHANGE, Map.of("credential_type", "x509",
                "change_type", "revoke", "x509_issuer", "CN=Example CA", "x509_serial", "0A1B2C"));
        assertThat(x509.x509Issuer()).isEqualTo("CN=Example CA");
        assertThat(x509.x509Serial()).isEqualTo("0A1B2C");
    }

    @Test
    void assuranceLevelChangeHasNamespaceLevelsAndDirection() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_ASSURANCE_LEVEL_CHANGE, Map.of("namespace", "NIST-AAL",
                "current_level", "nist-aal2", "previous_level", "nist-aal1", "change_direction", "increase"));
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.ASSURANCE_LEVEL_CHANGE);
        assertThat(event.namespace()).isEqualTo("NIST-AAL");
        assertThat(event.currentLevel()).isEqualTo("nist-aal2");
        assertThat(event.previousLevel()).isEqualTo("nist-aal1");
        assertThat(event.changeDirection()).isEqualTo("increase");
        SsfCaepEvent unknownPrevious = single(SsfEventTypes.CAEP_ASSURANCE_LEVEL_CHANGE,
                Map.of("namespace", "RFC8176", "current_level", "1"));
        assertThat(unknownPrevious.previousLevel()).isNull();
        assertThat(unknownPrevious.changeDirection()).isNull();
    }

    @Test
    void deviceComplianceChangeHasBothStatuses() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_DEVICE_COMPLIANCE_CHANGE, Map.of("previous_status", "compliant",
                "current_status", "not-compliant", "initiating_entity", "policy"));
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.DEVICE_COMPLIANCE_CHANGE);
        assertThat(event.previousStatus()).isEqualTo("compliant");
        assertThat(event.currentStatus()).isEqualTo("not-compliant");
    }

    @Test
    void sessionEventsDescribeTheSession() {
        SsfCaepEvent established = single(SsfEventTypes.CAEP_SESSION_ESTABLISHED,
                Map.of("fp_ua", "abb0b6e7da81a42233f8f2b1a8ddb1b9a4c81611", "acr", "AAL2", "amr", List.of("otp", 42),
                        "ext_id", "12345", "event_timestamp", 1615304991));
        assertThat(established.kind()).isEqualTo(SsfCaepEventKind.SESSION_ESTABLISHED);
        assertThat(established.fpUa()).isEqualTo("abb0b6e7da81a42233f8f2b1a8ddb1b9a4c81611");
        assertThat(established.acr()).isEqualTo("AAL2");
        assertThat(established.amr()).containsExactly("otp");
        assertThat(established.extId()).isEqualTo("12345");
        SsfCaepEvent presented = single(SsfEventTypes.CAEP_SESSION_PRESENTED,
                Map.of("fp_ua", "abb0b6e7da81a42233f8f2b1a8ddb1b9a4c81611", "ext_id", "12345"));
        assertThat(presented.kind()).isEqualTo(SsfCaepEventKind.SESSION_PRESENTED);
        assertThat(presented.acr()).isNull();
        assertThat(presented.amr()).isEmpty();
    }

    @Test
    void riskLevelChangeNamesThePrincipalAndTheLevels() {
        SsfCaepEvent event = single(SsfEventTypes.CAEP_RISK_LEVEL_CHANGE, Map.of("principal", "USER", "current_level",
                "LOW", "previous_level", "HIGH", "risk_reason", "PASSWORD_FOUND_IN_DATA_BREACH"));
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.RISK_LEVEL_CHANGE);
        assertThat(event.principal()).isEqualTo("USER");
        assertThat(event.currentLevel()).isEqualTo("LOW");
        assertThat(event.previousLevel()).isEqualTo("HIGH");
        assertThat(event.riskReason()).isEqualTo("PASSWORD_FOUND_IN_DATA_BREACH");
    }

    @Test
    void findsAllCaepEventsOfASetInOrderAndIgnoresOthers() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.RISC_ACCOUNT_DISABLED, Map.of("reason", "hijacking"));
        events.put(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("event_timestamp", 1615304991));
        events.put(UNKNOWN_CAEP_EVENT, "not an object");
        events.put(SsfEventTypes.SCIM_PROV_DELETE, Map.of());
        List<SsfCaepEvent> caepEvents = SsfCaepEvent.in(token(events));
        assertThat(caepEvents).extracting(SsfCaepEvent::kind)
            .containsExactly(SsfCaepEventKind.SESSION_REVOKED, SsfCaepEventKind.OTHER);
        assertThat(caepEvents.get(1).eventType()).isEqualTo(UNKNOWN_CAEP_EVENT);
        assertThat(caepEvents.get(1).payload()).isEmpty();
        assertThat(SsfCaepEvent.in(token(Map.of(SsfEventTypes.RISC_ACCOUNT_PURGED, Map.of())))).isEmpty();
    }

    @Test
    void ofResolvesAliasesAndRejectsOtherEventTypes() {
        SsfCaepEvent event = SsfCaepEvent.of("CaepSessionRevoked", null);
        assertThat(event.eventType()).isEqualTo(SsfEventTypes.CAEP_SESSION_REVOKED);
        assertThat(event.kind()).isEqualTo(SsfCaepEventKind.SESSION_REVOKED);
        assertThat(event.payload()).isEmpty();
        assertThat(SsfCaepEvent.of("RiscAccountDisabled", Map.of())).isNull();
    }

    private static SsfCaepEvent single(String eventType, Map<String, Object> payload) {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(eventType, payload);
        List<SsfCaepEvent> caepEvents = SsfCaepEvent.in(token(events));
        assertThat(caepEvents).hasSize(1);
        return caepEvents.get(0);
    }

    private static SsfEventToken token(Map<String, Object> events) {
        return new SsfEventToken("jti-1", "https://idp.example.com", Instant.parse("2026-10-06T10:00:00Z"), List.of(),
                events, email("someuser@somedomain.com"), "8675309", Map.of());
    }

}

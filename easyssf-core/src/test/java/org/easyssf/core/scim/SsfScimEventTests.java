package org.easyssf.core.scim;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;

class SsfScimEventTests {

    private static final String USER_URI = "/Users/44f6142df96bd6ab61e7521d9";

    @Test
    void everyScimEventTypeHasAnOperationAndAnAlias() {
        for (SsfScimOperation operation : SsfScimOperation.values()) {
            for (String eventType : operation.eventTypes()) {
                assertThat(eventType).startsWith("urn:ietf:params:scim:event:");
                assertThat(SsfScimOperation.of(eventType)).isEqualTo(operation);
                assertThat(SsfEventTypes.aliasOf(eventType)).startsWith("Scim").doesNotContain(":");
                assertThat(SsfScimOperation.of(SsfEventTypes.aliasOf(eventType))).isEqualTo(operation);
            }
        }
        assertThat(SsfScimOperation.of(SsfEventTypes.CAEP_SESSION_REVOKED)).isNull();
        assertThat(SsfScimOperation.of(null)).isNull();
        assertThat(SsfScimOperation.isScimEvent("ScimProvDelete")).isTrue();
        assertThat(SsfScimOperation.isScimEvent("CaepSessionRevoked")).isFalse();
    }

    @Test
    void provisioningOperationsAreTheOnesOfSection2dot4() {
        assertThat(SsfScimOperation.values()).filteredOn(SsfScimOperation::isProvisioning)
            .containsExactly(SsfScimOperation.CREATE, SsfScimOperation.PATCH, SsfScimOperation.PUT,
                    SsfScimOperation.DELETE, SsfScimOperation.ACTIVATE, SsfScimOperation.DEACTIVATE);
        assertThat(SsfScimOperation.CREATE.eventTypes()).containsExactly(SsfEventTypes.SCIM_PROV_CREATE_NOTICE,
                SsfEventTypes.SCIM_PROV_CREATE_FULL);
    }

    @Test
    void fullEventCarriesTheResourceAsData() {
        Map<String, Object> data = Map.of("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"), "userName",
                "jdoe");
        SsfScimEvent event = single(SsfEventTypes.SCIM_PROV_CREATE_FULL, Map.of("data", data, "version", "v1"));
        assertThat(event.operation()).isEqualTo(SsfScimOperation.CREATE);
        assertThat(event.isFull()).isTrue();
        assertThat(event.isNotice()).isFalse();
        assertThat(event.data()).isEqualTo(data);
        assertThat(event.attributes()).isEmpty();
        assertThat(event.version()).isEqualTo("v1");
        assertThat(event.subject().uri()).isEqualTo(USER_URI);
        assertThat(event.subject().externalId()).isEqualTo("jdoe");
    }

    @Test
    void noticeEventListsTheModifiedAttributes() {
        SsfScimEvent event = single(SsfEventTypes.SCIM_PROV_PATCH_NOTICE,
                Map.of("attributes", List.of("members", 42, "name.familyName")));
        assertThat(event.operation()).isEqualTo(SsfScimOperation.PATCH);
        assertThat(event.isNotice()).isTrue();
        assertThat(event.isFull()).isFalse();
        assertThat(event.attributes()).containsExactly("members", "name.familyName");
        assertThat(event.data()).isNull();
        assertThat(event.version()).isNull();
    }

    @Test
    void eventsWithoutPayloadAttributesAreNeitherFullNorNotice() {
        SsfScimEvent event = single(SsfEventTypes.SCIM_PROV_DELETE, Map.of());
        assertThat(event.isFull()).isFalse();
        assertThat(event.isNotice()).isFalse();
        assertThat(event.payload()).isEmpty();
        assertThat(single(SsfEventTypes.SCIM_FEED_ADD, null).payload()).isEmpty();
    }

    @Test
    void asyncResponseCarriesTheOutcomeOfTheRequest() {
        Map<String, Object> response = Map.of("scimType", "invalidSyntax", "detail", "Request is unparsable");
        SsfScimEvent event = single(SsfEventTypes.SCIM_MISC_ASYNC_RESPONSE,
                Map.of("method", "PUT", "status", 400, "response", response));
        assertThat(event.operation()).isEqualTo(SsfScimOperation.ASYNC_RESPONSE);
        assertThat(event.method()).isEqualTo("PUT");
        assertThat(event.status()).isEqualTo("400");
        assertThat(event.response()).isEqualTo(response);
        assertThat(single(SsfEventTypes.SCIM_PROV_DELETE, Map.of()).response()).isNull();
    }

    @Test
    void findsAllScimEventsOfASetInOrderAndIgnoresOthers() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of());
        events.put(SsfEventTypes.SCIM_PROV_PUT_FULL, Map.of("data", Map.of("userName", "jdoe")));
        events.put(SsfEventTypes.SCIM_FEED_ADD, "not an object");
        List<SsfScimEvent> scimEvents = SsfScimEvent.in(token(events, scim(USER_URI)));
        assertThat(scimEvents).extracting(SsfScimEvent::operation)
            .containsExactly(SsfScimOperation.PUT, SsfScimOperation.FEED_ADD);
        assertThat(scimEvents.get(1).payload()).isEmpty();
        assertThat(SsfScimEvent.in(token(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of()), opaque("abc"))))
            .isEmpty();
    }

    @Test
    void subjectIsNullUnlessTheSetHasAScimSubject() {
        SsfScimEvent event = SsfScimEvent.in(token(Map.of(SsfEventTypes.SCIM_PROV_DELETE, Map.of()), opaque("abc")))
            .get(0);
        assertThat(event.subject()).isNull();
        assertThat(SsfScimEvent.in(token(Map.of(SsfEventTypes.SCIM_PROV_DELETE, Map.of()), null)).get(0).subject())
            .isNull();
    }

    @Test
    void ofResolvesAliasesAndRejectsOtherEventTypes() {
        SsfScimEvent event = SsfScimEvent.of("ScimProvDeactivate", null, null);
        assertThat(event.eventType()).isEqualTo(SsfEventTypes.SCIM_PROV_DEACTIVATE);
        assertThat(event.operation()).isEqualTo(SsfScimOperation.DEACTIVATE);
        assertThat(SsfScimEvent.of("CaepSessionRevoked", null, Map.of())).isNull();
    }

    private static SsfScimEvent single(String eventType, Map<String, Object> payload) {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(eventType, payload);
        List<SsfScimEvent> scimEvents = SsfScimEvent.in(token(events, scim(USER_URI, "jdoe")));
        assertThat(scimEvents).hasSize(1);
        return scimEvents.get(0);
    }

    private static SsfEventToken token(Map<String, Object> events, Map<String, Object> subjectId) {
        return new SsfEventToken("jti-1", "https://scim.example.com", Instant.parse("2026-10-04T10:00:00Z"), List.of(),
                events, subjectId, "txn-1", Map.of());
    }

}

package org.easyssf.receiver.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.receiver.event.SsfEventContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;

class SsfSessionTerminationEventHandlerTests {

    private final List<SsfSubject> terminated = new ArrayList<>();

    private final SsfSessionTerminationEventHandler handler = new SsfSessionTerminationEventHandler((subject) -> {
        this.terminated.add(subject);
        return 1;
    }, List.of("CaepSessionRevoked"), List.of("CaepCredentialChange", "ScimProvDeactivate", "ScimProvDelete"));

    @Test
    void sessionEventTerminatesTheSessionNamed() {
        this.handler.handle(context(SsfEventTypes.CAEP_SESSION_REVOKED,
                complex(issSub("https://idp.example", "alice"), opaque("session-1"))));
        assertThat(this.terminated).hasSize(1);
        assertThat(this.terminated.get(0).sessionId()).isEqualTo("session-1");
    }

    @Test
    void userEventTerminatesAllSessionsOfTheUser() {
        this.handler.handle(context(SsfEventTypes.CAEP_CREDENTIAL_CHANGE,
                complex(issSub("https://idp.example", "alice"), opaque("session-1"))));
        assertThat(this.terminated).hasSize(1);
        assertThat(this.terminated.get(0).sessionId()).isNull();
        assertThat(this.terminated.get(0).subject()).isEqualTo("alice");
    }

    @Test
    void scimDeactivateAndDeleteTerminateTheSessionsOfTheResource() {
        this.handler.handle(context(SsfEventTypes.SCIM_PROV_DEACTIVATE, scim("/Users/2b2f880a", "alice")));
        this.handler.handle(context(SsfEventTypes.SCIM_PROV_DELETE, scim("/Users/2b2f880a")));
        this.handler.handle(context(SsfEventTypes.SCIM_PROV_ACTIVATE, scim("/Users/2b2f880a")));
        assertThat(this.terminated).hasSize(2)
            .allSatisfy((subject) -> assertThat(subject.userIdentifier().isScim()).isTrue());
    }

    @Test
    void eventWithoutSubjectTerminatesNothing() {
        this.handler.handle(context(SsfEventTypes.SCIM_PROV_DEACTIVATE, null));
        assertThat(this.terminated).isEmpty();
    }

    private static SsfEventContext context(String eventType, Map<String, Object> subjectId) {
        return new SsfEventContext(
                new SsfEventToken("jti-1", "https://idp.example", Instant.parse("2026-10-04T10:00:00Z"), List.of(),
                        Map.of(eventType, Map.of()), subjectId, null, Map.of()));
    }

}

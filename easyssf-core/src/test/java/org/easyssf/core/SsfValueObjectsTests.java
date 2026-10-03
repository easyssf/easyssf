package org.easyssf.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.stream.SsfStreamConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The value objects of the protocol copy what they are given and cannot be changed.
 */
class SsfValueObjectsTests {

    @Test
    void eventTokenCopiesItsCollections() {
        List<String> aud = new ArrayList<>(List.of("receiver"));
        Map<String, Object> events = new HashMap<>(Map.of("urn:example:event", Map.of()));
        Map<String, Object> claims = new HashMap<>(Map.of("jti", "1"));
        SsfEventToken token = new SsfEventToken("1", "https://tr.example", Instant.EPOCH, aud, events, null, null,
                claims);
        aud.clear();
        events.clear();
        claims.put("x", "y");
        assertThat(token.aud()).containsExactly("receiver");
        assertThat(token.events()).containsOnlyKeys("urn:example:event");
        assertThat(token.claims()).containsOnlyKeys("jti");
        assertThat(token.subjectId()).isNull();
        assertThatThrownBy(() -> token.events().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nestedMapsAndListsAreCopiedAndUnmodifiableToo() {
        Map<String, Object> user = new HashMap<>(Map.of("format", "email", "email", "alice@example.com"));
        List<Object> roles = new ArrayList<>(List.of("admin", Map.of("name", "ops")));
        Map<String, Object> subjectId = new HashMap<>(Map.of("format", "complex", "user", user, "roles", roles));
        SsfSubject subject = SsfSubject.from(subjectId);
        user.put("email", "mallory@example.com");
        roles.clear();
        assertThat(subject.user().value()).isEqualTo("alice@example.com");
        assertThat(subject.raw().get("roles")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(2);
        assertThatThrownBy(() -> ((Map<?, ?>) subject.raw().get("user")).clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) subject.raw().get("roles")).clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) ((List<?>) subject.raw().get("roles")).get(1)).clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void streamConfigurationCopiesItsClaimsAndKeepsNullValues() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("stream_id", "s-1");
        claims.put("description", null);
        SsfStreamConfiguration stream = new SsfStreamConfiguration(claims);
        claims.put("stream_id", "s-2");
        assertThat(stream.streamId()).isEqualTo("s-1");
        assertThat(stream.claims()).containsKey("description");
        assertThat(stream.description()).isNull();
        assertThatThrownBy(() -> stream.claims().remove("stream_id")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new SsfStreamConfiguration(null).claims()).isEmpty();
    }

}

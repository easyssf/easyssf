package org.easyssf.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
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

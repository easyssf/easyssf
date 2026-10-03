package org.easyssf.receiver.transmitter;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.easyssf.receiver.http.ScriptedSsfHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientCredentialsSsfTransmitterTokenProviderTests {

    private static final String TOKEN_RESPONSE = "{\"access_token\":\"t-%d\",\"token_type\":\"Bearer\",\"expires_in\":300}";

    private final ScriptedSsfHttpClient http = new ScriptedSsfHttpClient();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T10:00:00Z"));

    private final ClientCredentialsSsfTransmitterTokenProvider provider = new ClientCredentialsSsfTransmitterTokenProvider(
            this.http, URI.create("https://idp.example/token"), "receiver", "secret");

    ClientCredentialsSsfTransmitterTokenProviderTests() {
        this.provider.setClock(this.clock);
    }

    @Test
    void sendsTheGrantWithScopesAndAdditionalParameters() {
        this.provider.setScopes(List.of("ssf.read", "ssf.manage"));
        this.provider.setAdditionalParameters(Map.of("audience", "https://tr.example", "grant_type", "ignored"));
        this.http.respond(200, TOKEN_RESPONSE.formatted(1));
        assertThat(this.provider.getAccessToken()).isEqualTo("t-1");
        String body = this.http.requests().get(0).body();
        assertThat(body).contains("grant_type=client_credentials")
            .contains("scope=ssf.read+ssf.manage")
            .contains("audience=https%3A%2F%2Ftr.example")
            .doesNotContain("grant_type=ignored");
        assertThat(this.http.requests().get(0).headers()).containsKey("Authorization");
    }

    @Test
    void renewsTheTokenAheadOfItsExpiryByTheSafetyWindow() {
        this.http.respond(200, TOKEN_RESPONSE.formatted(1)).respond(200, TOKEN_RESPONSE.formatted(2));
        assertThat(this.provider.getAccessToken()).isEqualTo("t-1");
        // 300s lifetime, 30s default window: renewed after 270s
        this.clock.advance(Duration.ofSeconds(269));
        assertThat(this.provider.getAccessToken()).isEqualTo("t-1");
        this.clock.advance(Duration.ofSeconds(2));
        assertThat(this.provider.getAccessToken()).isEqualTo("t-2");
        assertThat(this.http.requests()).hasSize(2);
    }

    @Test
    void safetyWindowIsConfigurableButAtMostAQuarterOfTheLifetime() {
        this.provider.setExpirySafetyWindow(Duration.ofSeconds(100));
        this.http.respond(200, TOKEN_RESPONSE.formatted(1)).respond(200, TOKEN_RESPONSE.formatted(2));
        this.provider.getAccessToken();
        // a quarter of 300s is 75s: renewed after 225s, not after 200s
        this.clock.advance(Duration.ofSeconds(224));
        assertThat(this.provider.getAccessToken()).isEqualTo("t-1");
        this.clock.advance(Duration.ofSeconds(2));
        assertThat(this.provider.getAccessToken()).isEqualTo("t-2");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

    }

}

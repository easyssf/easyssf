package org.easyssf.core.metadata;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsfTransmitterMetadataTests {

    @Test
    void exposesTheMembersOfTheFinalSpecification() {
        Map<String, Object> claims = Map.of("issuer", "https://tr.example", "spec_version", "1_0",
                "delivery_methods_supported", List.of(SsfDeliveryMethod.PUSH.uri()), "critical_subject_members",
                List.of("user", "tenant"), "authorization_schemes", List.of(Map.of("spec_urn", "urn:ietf:rfc:6749")),
                "default_subjects", "ALL");
        SsfTransmitterMetadata metadata = new SsfTransmitterMetadata("https://tr.example", null, claims);
        assertThat(metadata.specVersion()).isEqualTo(SsfTransmitterMetadata.SPEC_VERSION_1_0);
        assertThat(metadata.deliveryMethodsSupported()).containsExactly(SsfDeliveryMethod.PUSH.uri());
        assertThat(metadata.supportsDeliveryMethod(SsfDeliveryMethod.PUSH)).isTrue();
        assertThat(metadata.supportsDeliveryMethod(SsfDeliveryMethod.POLL)).isFalse();
        assertThat(metadata.criticalSubjectMembers()).containsExactly("user", "tenant");
        assertThat(metadata.authorizationSchemes()).singleElement()
            .satisfies((scheme) -> assertThat(scheme).containsEntry("spec_urn", "urn:ietf:rfc:6749"));
        assertThat(metadata.defaultSubjects()).isEqualTo("ALL");
    }

    @Test
    void absentMembersAreEmptyAndEveryDeliveryMethodIsAssumedToBeSupported() {
        SsfTransmitterMetadata metadata = new SsfTransmitterMetadata("https://tr.example",
                URI.create("https://tr.example/jwks"), Map.of("issuer", "https://tr.example"));
        assertThat(metadata.specVersion()).isNull();
        assertThat(metadata.deliveryMethodsSupported()).isEmpty();
        assertThat(metadata.supportsDeliveryMethod(SsfDeliveryMethod.POLL)).isTrue();
        assertThat(metadata.criticalSubjectMembers()).isEmpty();
        assertThat(metadata.authorizationSchemes()).isEmpty();
        assertThat(metadata.defaultSubjects()).isNull();
        assertThat(metadata.configurationEndpoint()).isNull();
    }

    @Test
    void claimsAreCopiedAndUnmodifiable() {
        Map<String, Object> claims = new HashMap<>(Map.of("issuer", "https://tr.example"));
        SsfTransmitterMetadata metadata = new SsfTransmitterMetadata("https://tr.example", null, claims);
        claims.put("spec_version", "1_0");
        assertThat(metadata.specVersion()).isNull();
        assertThatThrownBy(() -> metadata.claims().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

}

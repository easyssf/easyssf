package org.easyssf.receiver.transmitter;

import java.net.URI;

import org.easyssf.core.metadata.SsfTransmitterMetadata;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.test.TestTransmitter;
import org.easyssf.test.TestTransmitter.MetadataLocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SsfTransmitterMetadataResolverTests {

    @Test
    void insertsWellKnownPathBetweenHostAndIssuerPath() {
        assertThat(SsfTransmitterMetadataResolver.deriveMetadataUris("https://tr.example.com/issuer1")).containsExactly(
                URI.create("https://tr.example.com/.well-known/ssf-configuration/issuer1"),
                URI.create("https://tr.example.com/issuer1/.well-known/ssf-configuration"));
    }

    @Test
    void issuerWithoutPathHasSingleLocation() {
        URI expected = URI.create("https://tr.example.com/.well-known/ssf-configuration");
        assertThat(SsfTransmitterMetadataResolver.deriveMetadataUris("https://tr.example.com"))
            .containsExactly(expected);
        assertThat(SsfTransmitterMetadataResolver.deriveMetadataUris("https://tr.example.com/"))
            .containsExactly(expected);
    }

    @Test
    void rejectsRelativeIssuer() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SsfTransmitterMetadataResolver.deriveMetadataUris("/realms/test"));
    }

    @Test
    void resolvesMetadataFromSsfLocation() {
        try (TestTransmitter transmitter = new TestTransmitter(MetadataLocation.SSF)) {
            SsfTransmitterMetadata metadata = resolver(transmitter, transmitter.issuer()).resolve();
            assertThat(metadata.issuer()).isEqualTo(transmitter.issuer());
            assertThat(metadata.jwksUri()).hasToString(transmitter.jwksUri());
        }
    }

    @Test
    void fallsBackToLocationAppendedToIssuer() {
        try (TestTransmitter transmitter = new TestTransmitter(MetadataLocation.OIDC_STYLE)) {
            assertThat(resolver(transmitter, transmitter.issuer()).resolve().jwksUri())
                .hasToString(transmitter.jwksUri());
        }
    }

    @Test
    void unresolvableMetadataIsReportedAsUnavailableAndRetried() {
        try (TestTransmitter transmitter = new TestTransmitter(MetadataLocation.NONE)) {
            SsfTransmitterMetadataResolver resolver = resolver(transmitter, transmitter.issuer());
            assertThatExceptionOfType(SsfTransmitterUnavailableException.class).isThrownBy(resolver::resolve);
            assertThatExceptionOfType(SsfTransmitterUnavailableException.class).isThrownBy(resolver::resolve);
        }
    }

    @Test
    void rejectsMetadataOfAnotherIssuer() {
        try (TestTransmitter transmitter = new TestTransmitter(MetadataLocation.SSF)) {
            URI metadataUri = SsfTransmitterMetadataResolver.deriveMetadataUris(transmitter.issuer()).get(0);
            SsfTransmitterMetadataResolver resolver = new SsfTransmitterMetadataResolver("https://other.example",
                    metadataUri, new JdkSsfHttpClient());
            assertThatExceptionOfType(SsfTransmitterUnavailableException.class).isThrownBy(resolver::resolve)
                .withStackTraceContaining("does not match the configured transmitter issuer");
        }
    }

    private static SsfTransmitterMetadataResolver resolver(TestTransmitter transmitter, String issuer) {
        return new SsfTransmitterMetadataResolver(issuer, null, new JdkSsfHttpClient());
    }

}

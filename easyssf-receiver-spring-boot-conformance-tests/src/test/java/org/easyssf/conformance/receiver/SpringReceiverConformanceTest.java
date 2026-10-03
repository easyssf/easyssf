package org.easyssf.conformance.receiver;

import org.easyssf.test.conformance.ConformanceSuite;
import org.easyssf.test.conformance.receiver.AbstractReceiverConformanceTest;
import org.easyssf.test.conformance.receiver.ConformanceRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The conformance plans against the Spring Boot receiver under test,
 * {@link CtsApplication}.
 */
@SpringBootTest(classes = CtsApplication.class, webEnvironment = WebEnvironment.DEFINED_PORT)
abstract class SpringReceiverConformanceTest extends AbstractReceiverConformanceTest {

    @Autowired
    private ConformanceRunner runner;

    @DynamicPropertySource
    static void receiverProperties(DynamicPropertyRegistry registry) {
        ConformanceSuite suite = ConformanceSuite.instance();
        registry.add("server.port", AbstractReceiverConformanceTest::receiverPort);
        registry.add("server.ssl.certificate", () -> "classpath:" + RECEIVER_CERTIFICATE);
        registry.add("server.ssl.certificate-private-key", () -> "classpath:" + RECEIVER_PRIVATE_KEY);
        registry.add("spring.ssl.bundle.pem.conformance-suite.truststore.certificate",
                () -> "file:" + suite.certificateFile());
        registry.add("cts.transmitter.ssl-bundle", () -> "conformance-suite");
        registry.add("cts.delivery.push-url", () -> receiverPushUrl(suite));
    }

    @Override
    protected ConformanceRunner runner() {
        return this.runner;
    }

}

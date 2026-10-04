package org.easyssf.examples.scim;

import org.easyssf.test.TestTransmitter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Mirrors the users of a SCIM service provider into a local {@link UserDirectory} from
 * the SCIM Events (RFC 9967) the provider transmits over SSF. The receiver of the starter
 * polls the transmitter and hands the events to {@link ScimProvisioningHandler}; the
 * directory is published at {@code /users}.
 *
 * <p>
 * Keycloak does not emit SCIM Events, so the example brings its own transmitter: the
 * in-process {@link TestTransmitter} of {@code easyssf-test}, which
 * {@link DemoScimProvider} feeds with the life of two users. {@link #main} starts it
 * before the application, because the receiver needs the issuer of its transmitter in the
 * configuration. Against a real SCIM service provider, {@code SpringApplication.run(...)}
 * and the {@code easyssf.receiver.*} properties of {@code application.yaml} are all that
 * is needed.
 */
@SpringBootApplication
public class ExampleScimProvisioningApplication {

    public static void main(String[] args) {
        TestTransmitter transmitter = new TestTransmitter();
        new SpringApplicationBuilder(ExampleScimProvisioningApplication.class)
            .properties("easyssf.receiver.transmitter-issuer=" + transmitter.issuer())
            .profiles("demo")
            .initializers((context) -> {
                context.getBeanFactory().registerSingleton("demoTransmitter", transmitter);
                context.addApplicationListener((event) -> {
                    if (event instanceof ContextClosedEvent) {
                        transmitter.close();
                    }
                });
            })
            .run(args);
    }

}

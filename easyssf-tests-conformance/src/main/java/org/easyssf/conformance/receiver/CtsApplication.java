package org.easyssf.conformance.receiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;

/**
 * The receiver under test for the OpenID Foundation conformance suite. Each test run
 * assembles a fresh receiver from the easyssf receiver library, see
 * {@link ConformanceRun}, and plays the sequence of stream operations the test expects.
 */
@SpringBootApplication
@EnableConfigurationProperties(CtsProperties.class)
public class CtsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CtsApplication.class);
        // the JDK HTTP client reads this before the first client is created
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) (event) -> {
            if (!event.getEnvironment().getProperty("cts.transmitter.verify-hostname", Boolean.class, true)) {
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            }
        });
        application.run(args);
    }

}

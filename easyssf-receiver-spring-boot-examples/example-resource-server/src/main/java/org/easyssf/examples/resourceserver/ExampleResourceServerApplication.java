package org.easyssf.examples.resourceserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A resource server with Spring Boot's default security configuration: every request
 * needs an access token issued by Keycloak. The SSF receiver starter adds the push
 * endpoint and rejects the access tokens of sessions that were revoked in Keycloak.
 */
@SpringBootApplication
public class ExampleResourceServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleResourceServerApplication.class, args);
    }

}

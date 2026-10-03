package org.easyssf.examples.oidcclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A web application that logs users in with Keycloak. The SSF receiver starter adds the
 * push endpoint and ends the local session of a user when Keycloak reports that their
 * session was revoked or their credentials changed.
 */
@SpringBootApplication
public class ExampleOidcClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleOidcClientApplication.class, args);
    }

}

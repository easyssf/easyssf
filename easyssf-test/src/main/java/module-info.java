/**
 * {@code TestTransmitter}, an in-process SSF transmitter for the tests of a receiver. Its
 * API takes Nimbus keys and claims, so Nimbus is transitive.
 */
module org.easyssf.test {
    requires transitive org.easyssf.core;
    requires transitive com.nimbusds.jose.jwt;
    requires jdk.httpserver;

    exports org.easyssf.test;
}

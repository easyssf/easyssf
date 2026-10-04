/**
 * A framework-free SSF receiver: SET verification, push and poll delivery, stream
 * management and the event handlers. Micrometer is optional, for
 * {@code MicrometerSsfReceiverMetrics}.
 */
module org.easyssf.receiver {
    requires transitive org.easyssf.core;
    requires com.nimbusds.jose.jwt;
    requires org.slf4j;
    requires java.net.http;
    requires static micrometer.core;

    exports org.easyssf.receiver.event;
    exports org.easyssf.receiver.http;
    exports org.easyssf.receiver.metrics;
    exports org.easyssf.receiver.poll;
    exports org.easyssf.receiver.push;
    exports org.easyssf.receiver.revocation;
    exports org.easyssf.receiver.scim;
    exports org.easyssf.receiver.session;
    exports org.easyssf.receiver.set;
    exports org.easyssf.receiver.stream;
    exports org.easyssf.receiver.transmitter;
}

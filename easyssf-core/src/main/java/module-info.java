/**
 * The vocabulary of the Shared Signals Framework: event types, subjects, transmitter
 * metadata and stream configuration. Depends on nothing but the JDK.
 */
module org.easyssf.core {
    exports org.easyssf.core;
    exports org.easyssf.core.event;
    exports org.easyssf.core.metadata;
    exports org.easyssf.core.stream;
    exports org.easyssf.core.support;
}

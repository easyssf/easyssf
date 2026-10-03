package org.easyssf.receiver.spring.boot;

import org.easyssf.receiver.transmitter.SsfTransmitter;

/**
 * Callback to customize a transmitter before it is built, for transmitters configured
 * under {@code easyssf.receiver.transmitters.<name>.*}, whose parts are not beans. Every
 * bean of this type is called for every transmitter, the default one included; the
 * {@link SsfTransmitter.Builder#name() name} tells which.
 */
@FunctionalInterface
public interface SsfTransmitterCustomizer {

    void customize(SsfTransmitter.Builder builder);

}

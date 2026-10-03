package org.easyssf.conformance.receiver;

import org.easyssf.test.conformance.receiver.ConformanceReceiverSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@link ConformanceReceiverSettings settings of the receiver under test}, bound from
 * {@code cts.*}.
 */
@ConfigurationProperties("cts")
public class CtsProperties extends ConformanceReceiverSettings {

}

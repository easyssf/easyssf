package org.easyssf.receiver.stream;

import java.util.List;
import java.util.Optional;

import org.easyssf.core.stream.SsfStreamConfiguration;

/**
 * Holds the configuration of the stream this receiver gets its events from, once it is
 * known: after the {@link SsfStreamRegistrar} has registered or looked up the stream at
 * the transmitter.
 */
public class SsfReceiverStream {

    private volatile SsfStreamConfiguration configuration;

    /**
     * The configuration of the stream, empty as long as it is not known.
     */
    public Optional<SsfStreamConfiguration> getConfiguration() {
        return Optional.ofNullable(this.configuration);
    }

    public void setConfiguration(SsfStreamConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * The identifier of the stream, {@code null} as long as it is not known.
     */
    public String getStreamId() {
        SsfStreamConfiguration configuration = this.configuration;
        return (configuration != null) ? configuration.streamId() : null;
    }

    /**
     * The audiences of the SETs of the stream, empty as long as they are not known.
     */
    public List<String> getAudience() {
        SsfStreamConfiguration configuration = this.configuration;
        return (configuration != null) ? configuration.audience() : List.of();
    }

}

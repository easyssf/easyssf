package org.easyssf.receiver.transmitter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.set.IssuerRoutingSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.stream.SsfStreamVerification;

/**
 * The transmitters of a receiver, by name and by issuer. Most applications have one, the
 * {@link SsfTransmitter#DEFAULT_NAME default} one.
 */
public final class SsfTransmitters {

    private final Map<String, SsfTransmitter> byName;

    private final Map<String, SsfTransmitter> byIssuer;

    private final IssuerRoutingSsfSetVerifier verifier;

    public SsfTransmitters(Collection<SsfTransmitter> transmitters) {
        SsfAssert.isTrue(transmitters != null && !transmitters.isEmpty(), "transmitters must not be empty");
        Map<String, SsfTransmitter> byName = new LinkedHashMap<>();
        Map<String, SsfTransmitter> byIssuer = new LinkedHashMap<>();
        Map<String, SsfSetVerifier> verifiers = new LinkedHashMap<>();
        for (SsfTransmitter transmitter : transmitters) {
            SsfAssert.isTrue(byName.put(transmitter.getName(), transmitter) == null,
                    "Two transmitters are named '" + transmitter.getName() + "'");
            SsfTransmitter sameIssuer = byIssuer.put(transmitter.getIssuer(), transmitter);
            SsfAssert.isTrue(sameIssuer == null,
                    "The transmitters '" + transmitter.getName() + "' and '"
                            + ((sameIssuer != null) ? sameIssuer.getName() : "") + "' have the same issuer "
                            + transmitter.getIssuer());
            verifiers.put(transmitter.getIssuer(), transmitter.getVerifier());
        }
        this.byName = Collections.unmodifiableMap(byName);
        this.byIssuer = Collections.unmodifiableMap(byIssuer);
        this.verifier = new IssuerRoutingSsfSetVerifier(verifiers);
    }

    public List<SsfTransmitter> all() {
        return List.copyOf(this.byName.values());
    }

    public Optional<SsfTransmitter> get(String name) {
        return Optional.ofNullable(this.byName.get(name));
    }

    public Optional<SsfTransmitter> byIssuer(String issuer) {
        return Optional.ofNullable(this.byIssuer.get(issuer));
    }

    /**
     * @return the transmitter if there is exactly one, else the default one if there is
     * one
     */
    public Optional<SsfTransmitter> primary() {
        if (this.byName.size() == 1) {
            return Optional.of(this.byName.values().iterator().next());
        }
        return get(SsfTransmitter.DEFAULT_NAME);
    }

    /**
     * @return a verifier that verifies the SETs of every transmitter, by issuer
     */
    public SsfSetVerifier verifier() {
        return this.verifier;
    }

    /**
     * @return the stream verification of the transmitter with the given issuer,
     * {@code null} for an unknown issuer
     */
    public SsfStreamVerification streamVerification(String issuer) {
        SsfTransmitter transmitter = this.byIssuer.get(issuer);
        return (transmitter != null) ? transmitter.getStreamVerification() : null;
    }

    /**
     * @return the {@code Authorization} header the transmitter with the given issuer has
     * to send to the push endpoint, {@code null} if none is expected or the issuer is
     * unknown
     */
    public String pushAuthorizationHeader(String issuer) {
        SsfTransmitter transmitter = this.byIssuer.get(issuer);
        return (transmitter != null) ? transmitter.getPushAuthorizationHeader() : null;
    }

    /**
     * Starts the background work of every transmitter.
     */
    public void start() {
        this.byName.values().forEach(SsfTransmitter::start);
    }

    public void stop() {
        this.byName.values().forEach(SsfTransmitter::stop);
    }

}

package org.easyssf.receiver.transmitter;

import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.set.SsfSetVerifier;
import org.easyssf.receiver.stream.SsfReceiverStream;
import org.easyssf.receiver.stream.SsfStreamClient;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.easyssf.receiver.stream.SsfStreamVerification;

/**
 * Everything the receiver holds for one transmitter: how its SETs are verified, how it is
 * called, the stream of the receiver at it, and the background work for it. Build it with
 * {@link #builder(String, String)}.
 */
public final class SsfTransmitter {

    /**
     * The name of the transmitter an application configures without naming it.
     */
    public static final String DEFAULT_NAME = "default";

    private final String name;

    private final String issuer;

    private final SsfTransmitterMetadataResolver metadataResolver;

    private final SsfSetVerifier verifier;

    private final SsfTransmitterTokenProvider tokenProvider;

    private final SsfStreamClient streamClient;

    private final SsfReceiverStream receiverStream;

    private final SsfStreamVerification streamVerification;

    private final SsfStreamRegistrar streamRegistrar;

    private final SsfPoller poller;

    private final boolean autoStartPolling;

    private final String pushAuthorizationHeader;

    private SsfTransmitter(Builder builder) {
        this.name = builder.name;
        this.issuer = builder.issuer;
        this.metadataResolver = builder.metadataResolver;
        this.verifier = builder.verifier;
        this.tokenProvider = builder.tokenProvider;
        this.streamClient = builder.streamClient;
        this.receiverStream = builder.receiverStream;
        this.streamVerification = builder.streamVerification;
        this.streamRegistrar = builder.streamRegistrar;
        this.poller = builder.poller;
        this.autoStartPolling = builder.autoStartPolling;
        this.pushAuthorizationHeader = builder.pushAuthorizationHeader;
        if (this.streamRegistrar != null && this.poller != null) {
            // the first poll right after the stream is registered, not an interval later
            this.streamRegistrar.addListener((stream) -> this.poller.wakeUp());
        }
    }

    /**
     * @param name the name of the transmitter, {@link #DEFAULT_NAME} for an unnamed one
     * @param issuer the issuer of the transmitter
     */
    public static Builder builder(String name, String issuer) {
        return new Builder(name, issuer);
    }

    public String getName() {
        return this.name;
    }

    public String getIssuer() {
        return this.issuer;
    }

    public SsfTransmitterMetadataResolver getMetadataResolver() {
        return this.metadataResolver;
    }

    public SsfSetVerifier getVerifier() {
        return this.verifier;
    }

    /**
     * @return the token provider, {@code null} if the transmitter is not called
     */
    public SsfTransmitterTokenProvider getTokenProvider() {
        return this.tokenProvider;
    }

    /**
     * @return the stream client, {@code null} if the transmitter is not called
     */
    public SsfStreamClient getStreamClient() {
        return this.streamClient;
    }

    public SsfReceiverStream getReceiverStream() {
        return this.receiverStream;
    }

    public SsfStreamVerification getStreamVerification() {
        return this.streamVerification;
    }

    /**
     * @return the registrar, {@code null} if the stream is neither looked up nor managed
     */
    public SsfStreamRegistrar getStreamRegistrar() {
        return this.streamRegistrar;
    }

    /**
     * @return the poller, {@code null} with PUSH delivery
     */
    public SsfPoller getPoller() {
        return this.poller;
    }

    /**
     * @return the {@code Authorization} header the transmitter has to send to the push
     * endpoint, {@code null} if none is expected
     */
    public String getPushAuthorizationHeader() {
        return this.pushAuthorizationHeader;
    }

    /**
     * Starts the background work: looking up or registering the stream, and polling.
     */
    public void start() {
        if (this.streamRegistrar != null) {
            this.streamRegistrar.start();
        }
        if (this.poller != null && this.autoStartPolling) {
            this.poller.start();
        }
    }

    public void stop() {
        if (this.poller != null) {
            this.poller.stop();
        }
        if (this.streamRegistrar != null) {
            this.streamRegistrar.stop();
        }
    }

    @Override
    public String toString() {
        return this.name + " (" + this.issuer + ")";
    }

    public static final class Builder {

        private final String name;

        private final String issuer;

        private SsfTransmitterMetadataResolver metadataResolver;

        private SsfSetVerifier verifier;

        private SsfTransmitterTokenProvider tokenProvider;

        private SsfStreamClient streamClient;

        private SsfReceiverStream receiverStream = new SsfReceiverStream();

        private SsfStreamVerification streamVerification;

        private SsfStreamRegistrar streamRegistrar;

        private SsfPoller poller;

        private boolean autoStartPolling = true;

        private String pushAuthorizationHeader;

        private Builder(String name, String issuer) {
            SsfAssert.hasText(name, "name must not be empty");
            SsfAssert.hasText(issuer, "issuer must not be empty");
            this.name = name;
            this.issuer = issuer;
        }

        public String name() {
            return this.name;
        }

        public String issuer() {
            return this.issuer;
        }

        public Builder metadataResolver(SsfTransmitterMetadataResolver metadataResolver) {
            this.metadataResolver = metadataResolver;
            return this;
        }

        public SsfTransmitterMetadataResolver metadataResolver() {
            return this.metadataResolver;
        }

        public Builder verifier(SsfSetVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        public SsfSetVerifier verifier() {
            return this.verifier;
        }

        public Builder tokenProvider(SsfTransmitterTokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
            return this;
        }

        public SsfTransmitterTokenProvider tokenProvider() {
            return this.tokenProvider;
        }

        public Builder streamClient(SsfStreamClient streamClient) {
            this.streamClient = streamClient;
            return this;
        }

        public SsfStreamClient streamClient() {
            return this.streamClient;
        }

        public Builder receiverStream(SsfReceiverStream receiverStream) {
            SsfAssert.notNull(receiverStream, "receiverStream must not be null");
            this.receiverStream = receiverStream;
            return this;
        }

        public SsfReceiverStream receiverStream() {
            return this.receiverStream;
        }

        public Builder streamVerification(SsfStreamVerification streamVerification) {
            this.streamVerification = streamVerification;
            return this;
        }

        public SsfStreamVerification streamVerification() {
            return this.streamVerification;
        }

        public Builder streamRegistrar(SsfStreamRegistrar streamRegistrar) {
            this.streamRegistrar = streamRegistrar;
            return this;
        }

        public SsfStreamRegistrar streamRegistrar() {
            return this.streamRegistrar;
        }

        /**
         * @param poller the poller, {@code null} with PUSH delivery
         * @param autoStart whether {@link SsfTransmitter#start()} starts it
         */
        public Builder poller(SsfPoller poller, boolean autoStart) {
            this.poller = poller;
            this.autoStartPolling = autoStart;
            return this;
        }

        public SsfPoller poller() {
            return this.poller;
        }

        public Builder pushAuthorizationHeader(String pushAuthorizationHeader) {
            this.pushAuthorizationHeader = (pushAuthorizationHeader != null && !pushAuthorizationHeader.isBlank())
                    ? pushAuthorizationHeader : null;
            return this;
        }

        public String pushAuthorizationHeader() {
            return this.pushAuthorizationHeader;
        }

        public SsfTransmitter build() {
            SsfAssert.notNull(this.metadataResolver, "metadataResolver must not be null");
            SsfAssert.notNull(this.verifier, "verifier must not be null");
            if (this.streamVerification == null) {
                this.streamVerification = new SsfStreamVerification();
                this.streamVerification.setStreamId(this.receiverStream::getStreamId);
            }
            return new SsfTransmitter(this);
        }

    }

}

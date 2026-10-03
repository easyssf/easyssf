package org.easyssf.receiver.spring.boot;

import org.easyssf.receiver.poll.SsfPoller;
import org.easyssf.receiver.stream.SsfStreamRegistrar;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the background work of the receiver once the application context is ready and
 * stops it when the context is closed: looking up or registering the stream and polling
 * the transmitter.
 */
public class SsfReceiverLifecycle implements SmartLifecycle {

    private final SsfStreamRegistrar streamRegistrar;

    private final SsfPoller poller;

    private volatile boolean running;

    /**
     * @param streamRegistrar registers the stream, {@code null} if the stream is not
     * looked up or managed
     * @param poller polls the transmitter periodically, {@code null} if it is not polled
     * or only on demand
     */
    public SsfReceiverLifecycle(SsfStreamRegistrar streamRegistrar, SsfPoller poller) {
        this.streamRegistrar = streamRegistrar;
        this.poller = poller;
    }

    @Override
    public void start() {
        if (this.streamRegistrar != null) {
            this.streamRegistrar.start();
        }
        if (this.poller != null) {
            this.poller.start();
        }
        this.running = true;
    }

    @Override
    public void stop() {
        if (this.poller != null) {
            this.poller.stop();
        }
        if (this.streamRegistrar != null) {
            this.streamRegistrar.stop();
        }
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

}

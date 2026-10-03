package org.easyssf.receiver.spring.boot;

import org.easyssf.receiver.transmitter.SsfTransmitters;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the background work of the receiver once the application context is ready and
 * stops it when the context is closed: looking up or registering the streams and polling
 * the transmitters.
 */
public class SsfReceiverLifecycle implements SmartLifecycle {

    private final SsfTransmitters transmitters;

    private volatile boolean running;

    public SsfReceiverLifecycle(SsfTransmitters transmitters) {
        this.transmitters = transmitters;
    }

    @Override
    public void start() {
        this.transmitters.start();
        this.running = true;
    }

    @Override
    public void stop() {
        this.transmitters.stop();
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

}

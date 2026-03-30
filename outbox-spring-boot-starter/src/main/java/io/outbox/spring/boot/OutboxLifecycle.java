package io.outbox.spring.boot;

import io.outbox.Outbox;
import org.springframework.context.SmartLifecycle;

/**
 * Manages the outbox lifecycle: starts the poller after all listeners are registered,
 * and stops the poller/dispatcher during application shutdown.
 *
 * <p>Spring guarantees {@link org.springframework.beans.factory.SmartInitializingSingleton}
 * (used by {@link OutboxListenerRegistrar}) runs before {@link SmartLifecycle#start()}.
 * This eliminates the startup race where the poller could poll events before listeners
 * are registered.
 *
 * <p>{@link #stop()} delegates to {@link Outbox#close()}, which drains in-flight events
 * and shuts down the poller and dispatcher threads.
 */
public class OutboxLifecycle implements SmartLifecycle {

    private final Outbox outbox;
    private volatile boolean running;

    public OutboxLifecycle(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void start() {
        outbox.start();
        running = true;
    }

    @Override
    public void stop() {
        outbox.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

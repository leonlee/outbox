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
 *
 * <p>Runs in {@link #PHASE}, below the web server's graceful-shutdown phase
 * ({@code DEFAULT_PHASE - 1024}) and its stop phase ({@code DEFAULT_PHASE - 2048}), and below
 * the default phase of message listener containers. Spring stops the highest phase first, so the
 * outbox keeps dispatching while requests and consumers drain and stops after they have. Left at
 * the default it would stop first: events committed during the drain would miss the hot path,
 * and in multi-node — where every insert is stamped with this instance's owner — other nodes
 * would leave them for the full lock timeout. A custom producer that writes events while
 * stopping needs a phase above this one.
 */
public class OutboxLifecycle implements SmartLifecycle {

    /** Below every phase in which application traffic may still commit events. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

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

    @Override
    public int getPhase() {
        return PHASE;
    }
}

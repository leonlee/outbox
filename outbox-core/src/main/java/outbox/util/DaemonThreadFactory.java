package outbox.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory that creates named daemon threads with a sequential suffix.
 *
 * <p>Threads are named {@code <prefix>1}, {@code <prefix>2}, etc. All threads
 * are daemon threads so they do not prevent JVM shutdown.
 */
public final class DaemonThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    public DaemonThreadFactory(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}

package outbox.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory that creates named daemon threads.
 */
public final class DaemonThreadFactory implements ThreadFactory {
  private final String prefix;
  private final AtomicInteger counter = new AtomicInteger(1);

  public DaemonThreadFactory(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public Thread newThread(Runnable runnable) {
    Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
    thread.setDaemon(true);
    return thread;
  }
}

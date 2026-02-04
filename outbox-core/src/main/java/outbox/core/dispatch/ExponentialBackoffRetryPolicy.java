package outbox.core.dispatch;

import java.util.concurrent.ThreadLocalRandom;

public final class ExponentialBackoffRetryPolicy implements RetryPolicy {
  private final long baseDelayMs;
  private final long maxDelayMs;

  public ExponentialBackoffRetryPolicy(long baseDelayMs, long maxDelayMs) {
    this.baseDelayMs = baseDelayMs;
    this.maxDelayMs = maxDelayMs;
  }

  @Override
  public long computeDelayMs(int attempts) {
    if (attempts <= 0) {
      return 0L;
    }
    long expDelay;
    if (attempts >= 31) {
      expDelay = Long.MAX_VALUE;
    } else {
      expDelay = baseDelayMs * (1L << (attempts - 1));
    }
    long capped = Math.min(maxDelayMs, expDelay);
    double jitter = ThreadLocalRandom.current().nextDouble(0.5, 1.5);
    long withJitter = (long) (capped * jitter);
    return Math.min(maxDelayMs, Math.max(0L, withJitter));
  }
}

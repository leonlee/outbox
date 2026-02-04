package outbox.core.dispatch;

public interface RetryPolicy {
  long computeDelayMs(int attempts);
}

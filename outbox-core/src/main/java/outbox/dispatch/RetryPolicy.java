package outbox.dispatch;

public interface RetryPolicy {
  long computeDelayMs(int attempts);
}

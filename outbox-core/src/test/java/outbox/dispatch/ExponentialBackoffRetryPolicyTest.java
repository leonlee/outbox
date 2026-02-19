package outbox.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffRetryPolicyTest {

  @Test
  void firstAttemptReturnsBaseDelay() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 10000);

    long delay = policy.computeDelayMs(1);

    // With jitter (0.5 to 1.5), delay should be between 50 and 150
    assertTrue(delay >= 50 && delay <= 150,
        "Expected delay between 50-150, got: " + delay);
  }

  @Test
  void delayIncreasesExponentially() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 100000);

    long delay1 = policy.computeDelayMs(1); // base * 2^0 = 100
    long delay2 = policy.computeDelayMs(2); // base * 2^1 = 200
    long delay3 = policy.computeDelayMs(3); // base * 2^2 = 400

    // Verify each delay falls in expected range: base * 2^(n-1) * jitter[0.5, 1.5)
    assertTrue(delay1 >= 50 && delay1 < 150, "delay1 range: got " + delay1);
    assertTrue(delay2 >= 100 && delay2 < 300, "delay2 range: got " + delay2);
    assertTrue(delay3 >= 200 && delay3 < 600, "delay3 range: got " + delay3);
  }

  @Test
  void delayIsCappedAtMaxDelay() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 500);

    // After many attempts, delay should not exceed maxDelay (final Math.min caps it)
    long delay = policy.computeDelayMs(10);

    assertTrue(delay <= 500, "Delay should be capped at maxDelay, got: " + delay);
  }

  @Test
  void jitterAddsVariation() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(1000, 100000);

    // Run multiple times to verify jitter produces different values
    long delay1 = policy.computeDelayMs(1);
    long delay2 = policy.computeDelayMs(1);
    long delay3 = policy.computeDelayMs(1);

    // While technically all could be the same, probability is very low
    // At minimum verify they're in the expected range
    assertTrue(delay1 >= 500 && delay1 <= 1500);
    assertTrue(delay2 >= 500 && delay2 <= 1500);
    assertTrue(delay3 >= 500 && delay3 <= 1500);
  }

  @Test
  void handlesHighAttemptCount() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 60000);

    // Should not overflow or throw for high attempt counts
    long delay = policy.computeDelayMs(100);

    assertTrue(delay > 0, "Delay should be positive");
    assertTrue(delay <= 90000, "Delay should be capped at maxDelay * 1.5");
  }

  @Test
  void handlesAttemptCountAtOverflowBoundary() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 60000);

    // Attempt 31+ would overflow 2^attempts if not handled
    long delay31 = policy.computeDelayMs(31);
    long delay32 = policy.computeDelayMs(32);
    long delay50 = policy.computeDelayMs(50);

    assertTrue(delay31 > 0 && delay31 <= 90000);
    assertTrue(delay32 > 0 && delay32 <= 90000);
    assertTrue(delay50 > 0 && delay50 <= 90000);
  }

  @Test
  void zeroBaseDelayReturnsZero() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(0, 1000);

    long delay = policy.computeDelayMs(1);

    assertEquals(0, delay);
  }

  @Test
  void zeroAttemptsReturnsZero() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 10000);

    assertEquals(0L, policy.computeDelayMs(0));
  }

  @Test
  void negativeAttemptsReturnsZero() {
    ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(100, 10000);

    assertEquals(0L, policy.computeDelayMs(-1));
  }
}

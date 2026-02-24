package outbox.dispatch;

/**
 * Strategy for computing the delay before retrying a failed event dispatch.
 *
 * @see ExponentialBackoffRetryPolicy
 */
public interface RetryPolicy {

    /**
     * Computes the delay in milliseconds before the next retry attempt.
     *
     * @param attempts the number of attempts so far (1-based)
     * @return delay in milliseconds (non-negative)
     */
    long computeDelayMs(int attempts);
}

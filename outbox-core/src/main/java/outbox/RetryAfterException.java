package outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * Exception thrown by an {@link EventListener} to signal a failed attempt that
 * should be retried after a handler-specified delay.
 *
 * <p>Unlike {@link DispatchResult.RetryAfter}, throwing this exception <b>does</b>
 * count against {@code maxAttempts}. The dispatcher uses the exception's
 * {@link #retryAfter()} duration instead of the framework's {@link outbox.dispatch.RetryPolicy}.
 *
 * <p>Typical use cases:
 * <ul>
 *   <li>External API returned HTTP 429 with a {@code Retry-After} header</li>
 *   <li>Transient infrastructure failure with a known recovery window</li>
 * </ul>
 *
 * @see DispatchResult.RetryAfter
 */
public class RetryAfterException extends RuntimeException {

    private final Duration retryAfter;

    /**
     * Creates a new instance with the specified retry delay.
     *
     * @param retryAfter how long to wait before retrying
     * @throws NullPointerException     if {@code retryAfter} is null
     * @throws IllegalArgumentException if {@code retryAfter} is negative
     */
    public RetryAfterException(Duration retryAfter) {
        super("Retry after " + validate(retryAfter));
        this.retryAfter = retryAfter;
    }

    /**
     * Creates a new instance with the specified retry delay and message.
     *
     * @param retryAfter how long to wait before retrying
     * @param message    detail message
     * @throws NullPointerException     if {@code retryAfter} is null
     * @throws IllegalArgumentException if {@code retryAfter} is negative
     */
    public RetryAfterException(Duration retryAfter, String message) {
        super(message);
        this.retryAfter = validate(retryAfter);
    }

    /**
     * Creates a new instance with the specified retry delay and cause.
     *
     * @param retryAfter how long to wait before retrying
     * @param cause      the underlying cause
     * @throws NullPointerException     if {@code retryAfter} is null
     * @throws IllegalArgumentException if {@code retryAfter} is negative
     */
    public RetryAfterException(Duration retryAfter, Throwable cause) {
        super("Retry after " + validate(retryAfter), cause);
        this.retryAfter = retryAfter;
    }

    /**
     * Creates a new instance with the specified retry delay, message, and cause.
     *
     * @param retryAfter how long to wait before retrying
     * @param message    detail message
     * @param cause      the underlying cause
     * @throws NullPointerException     if {@code retryAfter} is null
     * @throws IllegalArgumentException if {@code retryAfter} is negative
     */
    public RetryAfterException(Duration retryAfter, String message, Throwable cause) {
        super(message, cause);
        this.retryAfter = validate(retryAfter);
    }

    /**
     * Returns the handler-requested retry delay.
     *
     * @return the retry delay (never null, never negative)
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    private static Duration validate(Duration retryAfter) {
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        return retryAfter;
    }
}

package io.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * Result returned by {@link EventListener#handleEvent(EventEnvelope)} to control
 * post-dispatch behavior.
 *
 * <ul>
 *   <li>{@link Done} — event processed successfully; marks the event as DONE.</li>
 *   <li>{@link RetryAfter} — event not yet complete; reschedules without counting
 *       against {@code maxAttempts}. Useful for polling external systems, respecting
 *       rate-limit {@code Retry-After} headers, or waiting for preconditions.</li>
 * </ul>
 *
 * @see EventListener#handleEvent(EventEnvelope)
 */
public sealed interface DispatchResult permits DispatchResult.Done, DispatchResult.RetryAfter {

    /**
     * Singleton indicating successful processing.
     */
    Done DONE = new Done();

    /**
     * Returns the singleton {@link Done} result.
     *
     * @return the DONE result
     */
    static Done done() {
        return DONE;
    }

    /**
     * Creates a {@link RetryAfter} result requesting re-delivery after the given delay.
     *
     * <p>This does <b>not</b> count as a failed attempt — the event's {@code attempts}
     * counter is not incremented.
     *
     * @param delay how long to wait before the next delivery attempt
     * @return a retry-after result
     * @throws NullPointerException     if {@code delay} is null
     * @throws IllegalArgumentException if {@code delay} is negative
     */
    static RetryAfter retryAfter(Duration delay) {
        return new RetryAfter(delay);
    }

    /**
     * Event processed successfully.
     */
    record Done() implements DispatchResult {
    }

    /**
     * Event not yet complete; reschedule after the specified delay.
     *
     * @param delay how long to wait before the next delivery attempt (must not be null or negative)
     */
    record RetryAfter(Duration delay) implements DispatchResult {
        public RetryAfter {
            Objects.requireNonNull(delay, "delay must not be null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
        }
    }
}

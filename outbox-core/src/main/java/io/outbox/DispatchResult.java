package io.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * Result returned by {@link EventListener#onEvent(EventEnvelope)} to control
 * post-dispatch behavior.
 *
 * <ul>
 *   <li>{@link Done} — event processed successfully; marks the event as DONE.</li>
 *   <li>{@link RetryAfter} — event not yet complete; reschedules without counting
 *       against {@code maxAttempts}. Useful for polling external systems, respecting
 *       rate-limit {@code Retry-After} headers, or waiting for preconditions.</li>
 *   <li>{@link Dead} — event cannot be processed; immediately marks the event as DEAD
 *       without retry. Useful when business logic determines the event is unprocessable.</li>
 * </ul>
 *
 * @see EventListener#onEvent(EventEnvelope)
 */
public sealed interface DispatchResult permits DispatchResult.Done, DispatchResult.RetryAfter, DispatchResult.Dead {

    /**
     * Singleton indicating successful processing.
     */
    Done DONE = new Done();

    /**
     * Singleton indicating the event should be immediately marked DEAD (no reason).
     */
    Dead DEAD = new Dead(null);

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
     * Returns the singleton {@link Dead} result with no reason.
     *
     * @return the DEAD result
     */
    static Dead dead() {
        return DEAD;
    }

    /**
     * Creates a {@link Dead} result with the given reason.
     *
     * @param reason optional explanation written to the outbox error column
     * @return a dead result
     */
    static Dead dead(String reason) {
        return new Dead(reason);
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

    /**
     * Event cannot be processed; immediately mark as DEAD without retry.
     *
     * @param reason optional explanation written to the outbox error column (may be null)
     */
    record Dead(String reason) implements DispatchResult {
    }
}

package outbox.poller;

import outbox.EventEnvelope;

/**
 * Callback for events discovered by the {@link OutboxPoller}. Implementations decide
 * how to process or forward polled events (e.g. enqueue into a dispatcher's cold queue).
 *
 * @see outbox.dispatch.DispatcherPollerHandler
 */
@FunctionalInterface
public interface OutboxPollerHandler {

    /**
     * Handles a polled event.
     *
     * @param event    the event envelope reconstructed from the database row
     * @param attempts the number of previous dispatch attempts
     * @return {@code true} if accepted, {@code false} to signal back-pressure (stops the current poll batch)
     */
    boolean handle(EventEnvelope event, int attempts);

    /**
     * Returns the number of events this handler can accept right now.
     * Used by the poller to cap claim size and avoid over-locking rows
     * that cannot be immediately processed. The poller skips the poll
     * cycle entirely when this returns {@code 0}.
     *
     * <p>Default returns {@link Integer#MAX_VALUE} (no cap).
     */
    default int availableCapacity() {
        return Integer.MAX_VALUE;
    }
}

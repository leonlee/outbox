package outbox;

import java.util.List;

/**
 * Lifecycle hook for {@link OutboxWriter} batch writes, replacing the former
 * {@code AfterCommitHook} with richer before/after semantics.
 *
 * <p>Use {@link outbox.dispatch.DispatcherWriterHook} to bridge into the
 * dispatcher's hot queue. The {@link #NOOP} instance does nothing, leaving
 * events for the poller fallback.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #beforeWrite} — may transform the event list; throwing aborts the write</li>
 *   <li>Store inserts events</li>
 *   <li>{@link #afterWrite} — observational, exceptions swallowed</li>
 *   <li>Transaction commits or rolls back</li>
 *   <li>{@link #afterCommit} or {@link #afterRollback} — exceptions swallowed</li>
 * </ol>
 *
 * @see OutboxWriter
 * @see outbox.dispatch.DispatcherWriterHook
 */
public interface WriterHook {

    /**
     * Called before events are inserted into the outbox store.
     *
     * <p>May return a modified list (filtering, enriching, reordering). Throwing
     * an exception aborts the entire write — no events are inserted.
     *
     * @param events immutable copy of the events to be written
     * @return the (possibly modified) event list to actually insert; {@code null} or empty suppresses the write
     */
    default List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
        return events;
    }

    /**
     * Called after events are inserted but before the transaction commits.
     * Exceptions are swallowed and logged.
     *
     * @param events the events that were inserted (post-{@code beforeWrite} transformation)
     */
    default void afterWrite(List<EventEnvelope> events) {
    }

    /**
     * Called after the enclosing transaction commits successfully.
     * Exceptions are swallowed and logged.
     *
     * @param events the events that were committed
     */
    default void afterCommit(List<EventEnvelope> events) {
    }

    /**
     * Called after the enclosing transaction rolls back.
     * Exceptions are swallowed and logged.
     *
     * @param events the events that were rolled back
     */
    default void afterRollback(List<EventEnvelope> events) {
    }

    /**
     * No-op hook that does nothing; used as the default in {@link OutboxWriter}.
     */
    WriterHook NOOP = new WriterHook() {
    };
}

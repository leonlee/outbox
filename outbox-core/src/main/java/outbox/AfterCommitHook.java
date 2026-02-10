package outbox;

/**
 * Callback invoked after a transaction commits to trigger hot-path event processing.
 *
 * <p>The default {@link #NOOP} instance does nothing, leaving events for the poller fallback.
 * Use {@link outbox.dispatch.DispatcherCommitHook} to bridge into the dispatcher's hot queue.
 *
 * @see OutboxWriter
 * @see outbox.dispatch.DispatcherCommitHook
 */
@FunctionalInterface
public interface AfterCommitHook {

  /**
   * Called after the enclosing transaction commits successfully.
   *
   * @param event the event that was persisted
   */
  void onCommit(EventEnvelope event);

  /** No-op hook that does nothing; used as the default in {@link OutboxWriter}. */
  AfterCommitHook NOOP = event -> {};
}

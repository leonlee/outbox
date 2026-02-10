package outbox;

@FunctionalInterface
public interface AfterCommitHook {
  void onCommit(EventEnvelope event);

  AfterCommitHook NOOP = event -> {};
}

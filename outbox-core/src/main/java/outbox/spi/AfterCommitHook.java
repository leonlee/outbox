package outbox.spi;

import outbox.EventEnvelope;

@FunctionalInterface
public interface AfterCommitHook {
  void onCommit(EventEnvelope event);

  AfterCommitHook NOOP = event -> {};
}

package outbox;

import outbox.model.OutboxEvent;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.ConnectionProvider;
import outbox.spi.EventPurger;
import outbox.spi.OutboxStore;
import outbox.spi.TxContext;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxTest {

  private static final ConnectionProvider STUB_CP = () -> { throw new SQLException("stub"); };

  private static final TxContext STUB_TX = new TxContext() {
    @Override public boolean isTransactionActive() { return false; }
    @Override public Connection currentConnection() { throw new IllegalStateException("stub"); }
    @Override public void afterCommit(Runnable cb) { throw new IllegalStateException("stub"); }
    @Override public void afterRollback(Runnable cb) { throw new IllegalStateException("stub"); }
  };

  private static final OutboxStore STUB_STORE = new OutboxStore() {
    @Override public void insertNew(Connection conn, EventEnvelope event) {}
    @Override public int markDone(Connection conn, String eventId) { return 0; }
    @Override public int markRetry(Connection conn, String eventId, Instant nextAt, String error) { return 0; }
    @Override public int markDead(Connection conn, String eventId, String error) { return 0; }
    @Override public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
      return List.of();
    }
  };

  private static final DefaultListenerRegistry STUB_REG = new DefaultListenerRegistry();

  // ── Validation: singleNode ───────────────────────────────────────

  @Test
  void singleNode_missingConnectionProvider_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode()
            .txContext(STUB_TX).outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
            .build());
  }

  @Test
  void singleNode_missingTxContext_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode()
            .connectionProvider(STUB_CP).outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
            .build());
  }

  @Test
  void singleNode_missingOutboxStore_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode()
            .connectionProvider(STUB_CP).txContext(STUB_TX).listenerRegistry(STUB_REG)
            .build());
  }

  @Test
  void singleNode_missingListenerRegistry_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode()
            .connectionProvider(STUB_CP).txContext(STUB_TX).outboxStore(STUB_STORE)
            .build());
  }

  // ── Validation: multiNode ────────────────────────────────────────

  @Test
  void multiNode_missingRequiredFields_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.multiNode()
            .claimLocking(Duration.ofMinutes(5))
            .build());
  }

  @Test
  void multiNode_withoutClaimLocking_throwsISE() {
    assertThrows(IllegalStateException.class, () ->
        Outbox.multiNode()
            .connectionProvider(STUB_CP).txContext(STUB_TX)
            .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
            .build());
  }

  // ── Validation: ordered ──────────────────────────────────────────

  @Test
  void ordered_missingRequiredFields_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.ordered()
            .build());
  }

  // ── Construction + lifecycle ─────────────────────────────────────

  @Test
  void singleNode_buildsAndClosesCleanly() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.singleNode()
          .connectionProvider(STUB_CP).txContext(STUB_TX)
          .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
          .intervalMs(60_000)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  @Test
  void multiNode_buildsAndClosesCleanly() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.multiNode()
          .connectionProvider(STUB_CP).txContext(STUB_TX)
          .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
          .claimLocking(Duration.ofMinutes(5))
          .intervalMs(60_000)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  @Test
  void ordered_buildsAndClosesCleanly() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.ordered()
          .connectionProvider(STUB_CP).txContext(STUB_TX)
          .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
          .intervalMs(60_000)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  @Test
  void multiNode_claimLockingWithExplicitOwnerId() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.multiNode()
          .connectionProvider(STUB_CP).txContext(STUB_TX)
          .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
          .claimLocking("node-1", Duration.ofMinutes(5))
          .intervalMs(60_000)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  // ── Validation: writerOnly ──────────────────────────────────────

  @Test
  void writerOnly_missingTxContext_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.writerOnly()
            .outboxStore(STUB_STORE)
            .build());
  }

  @Test
  void writerOnly_missingOutboxStore_throwsNPE() {
    assertThrows(NullPointerException.class, () ->
        Outbox.writerOnly()
            .txContext(STUB_TX)
            .build());
  }

  @Test
  void writerOnly_buildsAndClosesCleanly() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.writerOnly()
          .txContext(STUB_TX)
          .outboxStore(STUB_STORE)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  @Test
  void writerOnly_withPurger_missingConnectionProvider_throwsNPE() {
    EventPurger stubPurger = (conn, before, limit) -> 0;
    assertThrows(NullPointerException.class, () ->
        Outbox.writerOnly()
            .txContext(STUB_TX)
            .outboxStore(STUB_STORE)
            .purger(stubPurger)
            .build());
  }

  @Test
  void writerOnly_withPurger_buildsAndClosesCleanly() {
    EventPurger stubPurger = (conn, before, limit) -> 0;
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.writerOnly()
          .txContext(STUB_TX)
          .outboxStore(STUB_STORE)
          .connectionProvider(STUB_CP)
          .purger(stubPurger)
          .purgeRetention(Duration.ofHours(24))
          .purgeIntervalSeconds(1800)
          .purgeBatchSize(100)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  @Test
  void writerOnly_connectionProviderNotRequiredWithoutPurger() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.writerOnly()
          .txContext(STUB_TX)
          .outboxStore(STUB_STORE)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }

  // ── Double-build rejection ──────────────────────────────────────

  @Test
  void singleNode_doubleBuild_throwsISE() {
    var builder = Outbox.singleNode()
        .connectionProvider(STUB_CP).txContext(STUB_TX)
        .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
        .intervalMs(60_000);
    try (Outbox ignored = builder.build()) {
      assertThrows(IllegalStateException.class, builder::build);
    }
  }

  @Test
  void writerOnly_doubleBuild_throwsISE() {
    var builder = Outbox.writerOnly()
        .txContext(STUB_TX).outboxStore(STUB_STORE);
    try (Outbox ignored = builder.build()) {
      assertThrows(IllegalStateException.class, builder::build);
    }
  }

  @Test
  void singleNode_interceptorRejectsNull() {
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode().interceptor(null));
  }

  @Test
  void singleNode_interceptorsListRejectsNullList() {
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode().interceptors(null));
  }

  @Test
  void singleNode_interceptorsListRejectsNullElement() {
    List<outbox.dispatch.EventInterceptor> list = new java.util.ArrayList<>();
    list.add(null);
    assertThrows(NullPointerException.class, () ->
        Outbox.singleNode().interceptors(list));
  }

  // ── Fluent chaining returns correct type ─────────────────────────

  @Test
  void singleNode_fluentChaining() {
    assertDoesNotThrow(() -> {
      try (Outbox outbox = Outbox.singleNode()
          .connectionProvider(STUB_CP).txContext(STUB_TX)
          .outboxStore(STUB_STORE).listenerRegistry(STUB_REG)
          .workerCount(2)
          .hotQueueCapacity(500)
          .coldQueueCapacity(500)
          .maxAttempts(5)
          .batchSize(25)
          .intervalMs(60_000)
          .drainTimeoutMs(1000)
          .build()) {
        assertNotNull(outbox.writer());
      }
    });
  }
}

package outbox;

import outbox.spi.OutboxStore;
import outbox.spi.TxContext;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxWriterTest {

  @Test
  void writeThrowsWhenNoActiveTransaction() {
    StubTxContext txContext = new StubTxContext(false);
    RecordingOutboxStore store = new RecordingOutboxStore();

    OutboxWriter writer = new OutboxWriter(txContext, store);

    assertThrows(IllegalStateException.class, () ->
        writer.write(EventEnvelope.ofJson("Test", "{}")));
  }

  @Test
  void afterCommitHookRuns() {
    StubTxContext txContext = new StubTxContext(true);
    RecordingOutboxStore store = new RecordingOutboxStore();
    RecordingHook hook = new RecordingHook();

    OutboxWriter writer = new OutboxWriter(txContext, store, hook);

    writer.write(EventEnvelope.ofJson("Test", "{}"));

    assertDoesNotThrow(txContext::runAfterCommit);
    assertEquals(1, hook.callCount.get());
    assertEquals(1, store.insertCount.get());
  }

  @Test
  void afterCommitHookSwallowsExceptions() {
    StubTxContext txContext = new StubTxContext(true);
    RecordingOutboxStore store = new RecordingOutboxStore();
    AfterCommitHook hook = event -> { throw new RuntimeException("boom"); };

    OutboxWriter writer = new OutboxWriter(txContext, store, hook);

    writer.write(EventEnvelope.ofJson("Test", "{}"));

    assertDoesNotThrow(txContext::runAfterCommit);
    assertEquals(1, store.insertCount.get());
  }

  @Test
  void writeWithStringEventTypeAndPayload() {
    StubTxContext txContext = new StubTxContext(true);
    RecordingOutboxStore store = new RecordingOutboxStore();

    OutboxWriter writer = new OutboxWriter(txContext, store);

    String eventId = writer.write("UserCreated", "{\"id\":1}");
    assertNotNull(eventId);
    assertEquals(1, store.insertCount.get());
  }

  @Test
  void writeWithTypedEventTypeAndPayload() {
    StubTxContext txContext = new StubTxContext(true);
    RecordingOutboxStore store = new RecordingOutboxStore();

    OutboxWriter writer = new OutboxWriter(txContext, store);

    EventType eventType = StringEventType.of("OrderPlaced");
    String eventId = writer.write(eventType, "{\"orderId\":1}");
    assertNotNull(eventId);
    assertEquals(1, store.insertCount.get());
  }

  @Test
  void writeAllInsertsMultipleEvents() {
    StubTxContext txContext = new StubTxContext(true);
    RecordingOutboxStore store = new RecordingOutboxStore();

    OutboxWriter writer = new OutboxWriter(txContext, store);

    List<EventEnvelope> events = List.of(
        EventEnvelope.ofJson("EventA", "{}"),
        EventEnvelope.ofJson("EventB", "{}"),
        EventEnvelope.ofJson("EventC", "{}")
    );
    List<String> ids = writer.writeAll(events);
    assertEquals(3, ids.size());
    assertEquals(3, store.insertCount.get());
  }

  @Test
  void writeAllThrowsWhenNoActiveTransaction() {
    StubTxContext txContext = new StubTxContext(false);
    RecordingOutboxStore store = new RecordingOutboxStore();

    OutboxWriter writer = new OutboxWriter(txContext, store);

    assertThrows(IllegalStateException.class, () ->
        writer.writeAll(List.of(EventEnvelope.ofJson("Test", "{}"))));
  }

  private static final class StubTxContext implements TxContext {
    private final boolean active;
    private final List<Runnable> afterCommit = new ArrayList<>();
    private final List<Runnable> afterRollback = new ArrayList<>();

    private StubTxContext(boolean active) {
      this.active = active;
    }

    @Override
    public boolean isTransactionActive() {
      return active;
    }

    @Override
    public Connection currentConnection() {
      return null;
    }

    @Override
    public void afterCommit(Runnable callback) {
      afterCommit.add(callback);
    }

    @Override
    public void afterRollback(Runnable callback) {
      afterRollback.add(callback);
    }

    private void runAfterCommit() {
      for (Runnable callback : afterCommit) {
        callback.run();
      }
    }
  }

  private static final class RecordingOutboxStore implements OutboxStore {
    private final AtomicInteger insertCount = new AtomicInteger();

    @Override
    public void insertNew(Connection conn, EventEnvelope event) {
      insertCount.incrementAndGet();
    }

    @Override
    public int markDone(Connection conn, String eventId) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public int markDead(Connection conn, String eventId, String error) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public List<outbox.model.OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private static final class RecordingHook implements AfterCommitHook {
    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public void onCommit(EventEnvelope event) {
      callCount.incrementAndGet();
    }
  }
}

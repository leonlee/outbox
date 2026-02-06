package outbox;

import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.OutboxDispatcher;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.ConnectionProvider;
import outbox.spi.EventStore;
import outbox.spi.MetricsExporter;
import outbox.spi.TxContext;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxClientTest {

  @Test
  void publishThrowsWhenNoActiveTransaction() {
    StubTxContext txContext = new StubTxContext(false);
    RecordingEventStore store = new RecordingEventStore();
    OutboxDispatcher dispatcher = newDispatcher();

    OutboxClient client = new OutboxClient(txContext, store, dispatcher, MetricsExporter.NOOP);

    assertThrows(IllegalStateException.class, () ->
        client.publish(EventEnvelope.ofJson("Test", "{}")));

    dispatcher.close();
  }

  @Test
  void afterCommitSwallowsEnqueueExceptions() throws Exception {
    StubTxContext txContext = new StubTxContext(true);
    RecordingEventStore store = new RecordingEventStore();
    RecordingMetrics metrics = new RecordingMetrics();
    OutboxDispatcher dispatcher = newDispatcher();

    replaceHotQueueWithThrowingQueue(dispatcher);

    OutboxClient client = new OutboxClient(txContext, store, dispatcher, metrics);

    client.publish(EventEnvelope.ofJson("Test", "{}"));

    assertDoesNotThrow(txContext::runAfterCommit);
    assertEquals(1, store.insertCount.get());
    assertEquals(1, metrics.hotDropped.get());

    dispatcher.close();
  }

  private OutboxDispatcher newDispatcher() {
    ConnectionProvider connectionProvider = () -> { throw new SQLException("not used"); };
    EventStore store = new EventStore() {
      @Override
      public void insertNew(Connection conn, EventEnvelope event) {
      }

      @Override
      public int markDone(Connection conn, String eventId) {
        return 0;
      }

      @Override
      public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
        return 0;
      }

      @Override
      public int markDead(Connection conn, String eventId, String error) {
        return 0;
      }

      @Override
      public List<outbox.model.OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
        return List.of();
      }
    };

    return OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .eventStore(store)
        .listenerRegistry(new DefaultListenerRegistry())
        .inFlightTracker(new DefaultInFlightTracker())
        .retryPolicy(attempts -> 0L)
        .maxAttempts(1)
        .workerCount(0)
        .hotQueueCapacity(1)
        .coldQueueCapacity(1)
        .metrics(MetricsExporter.NOOP)
        .build();
  }

  private void replaceHotQueueWithThrowingQueue(OutboxDispatcher dispatcher) throws Exception {
    BlockingQueue<?> throwingQueue = (BlockingQueue<?>) Proxy.newProxyInstance(
        BlockingQueue.class.getClassLoader(),
        new Class<?>[]{BlockingQueue.class},
        (proxy, method, args) -> {
          if ("offer".equals(method.getName())) {
            throw new RuntimeException("boom");
          }
          Class<?> returnType = method.getReturnType();
          if (returnType == boolean.class) {
            return false;
          }
          if (returnType == int.class) {
            return 0;
          }
          if (returnType == long.class) {
            return 0L;
          }
          return null;
        }
    );

    Field hotQueueField = OutboxDispatcher.class.getDeclaredField("hotQueue");
    hotQueueField.setAccessible(true);
    hotQueueField.set(dispatcher, throwingQueue);
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

  private static final class RecordingEventStore implements EventStore {
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

  private static final class RecordingMetrics implements MetricsExporter {
    private final AtomicInteger hotDropped = new AtomicInteger();

    @Override
    public void incrementHotEnqueued() {
    }

    @Override
    public void incrementHotDropped() {
      hotDropped.incrementAndGet();
    }

    @Override
    public void incrementColdEnqueued() {
    }

    @Override
    public void incrementDispatchSuccess() {
    }

    @Override
    public void incrementDispatchFailure() {
    }

    @Override
    public void incrementDispatchDead() {
    }

    @Override
    public void recordQueueDepths(int hotDepth, int coldDepth) {
    }

    @Override
    public void recordOldestLagMs(long lagMs) {
    }
  }
}

package outbox.jdbc;

import outbox.EventEnvelope;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.InFlightTracker;
import outbox.dispatch.OutboxDispatcher;
import outbox.dispatch.QueuedEvent;
import outbox.model.EventStatus;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.MetricsExporter;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import outbox.jdbc.dialect.Dialects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDispatcherTest {
  private DataSource dataSource;
  private JdbcOutboxRepository repository;
  private DataSourceConnectionProvider connectionProvider;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_dispatcher_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.repository = new JdbcOutboxRepository(Dialects.get("h2"));
    this.connectionProvider = new DataSourceConnectionProvider(ds);

    try (Connection conn = ds.getConnection()) {
      createSchema(conn);
    }
  }

  @AfterEach
  void teardown() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute("DROP TABLE outbox_event");
    }
  }

  @Test
  void hotQueueIsPrioritizedWhenBothQueuesContainEvents() throws Exception {
    List<String> order = new CopyOnWriteArrayList<>();
    CountDownLatch processed = new CountDownLatch(2);

    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .registerAll(event -> {
          order.add(event.eventId());
          processed.countDown();
        });

    OutboxDispatcher dispatcher = new OutboxDispatcher(
        connectionProvider,
        repository,
        listeners,
        new DefaultInFlightTracker(),
        attempts -> 0L,
        10,
        0,
        10,
        10,
        MetricsExporter.NOOP
    );

    EventEnvelope cold = EventEnvelope.builder("Test").eventId("cold").payloadJson("{}").build();
    EventEnvelope hot = EventEnvelope.builder("Test").eventId("hot").payloadJson("{}").build();

    dispatcher.enqueueCold(new QueuedEvent(cold, QueuedEvent.Source.COLD, 0));
    dispatcher.enqueueHot(new QueuedEvent(hot, QueuedEvent.Source.HOT, 0));

    AtomicReference<Throwable> workerError = new AtomicReference<>();
    Thread worker = startWorker(dispatcher, workerError);

    assertTrue(processed.await(2, TimeUnit.SECONDS));

    dispatcher.close();
    worker.interrupt();
    worker.join(1000);

    if (workerError.get() != null) {
      throw new AssertionError("Worker failed", workerError.get());
    }

    assertEquals(List.of("hot", "cold"), order);
  }

  @Test
  void failureStopsSubsequentListenersAndMarksRetry() throws Exception {
    AtomicInteger secondCalled = new AtomicInteger();
    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .register("Test", event -> { throw new RuntimeException("boom"); })
        .register("Test", event -> secondCalled.incrementAndGet());

    OutboxDispatcher dispatcher = new OutboxDispatcher(
        connectionProvider,
        repository,
        listeners,
        new DefaultInFlightTracker(),
        attempts -> 0L,
        3,
        1,
        10,
        10,
        MetricsExporter.NOOP
    );

    EventEnvelope event = EventEnvelope.builder("Test").eventId("evt-retry").payloadJson("{}").build();
    insertEvent(event);

    dispatcher.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

    awaitStatus(event.eventId(), EventStatus.RETRY, 2_000);
    assertEquals(0, secondCalled.get());

    dispatcher.close();
  }

  @Test
  void failureMarksDeadWhenMaxAttemptsReached() throws Exception {
    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .register("Test", event -> { throw new RuntimeException("boom"); });

    OutboxDispatcher dispatcher = new OutboxDispatcher(
        connectionProvider,
        repository,
        listeners,
        new DefaultInFlightTracker(),
        attempts -> 0L,
        1,
        1,
        10,
        10,
        MetricsExporter.NOOP
    );

    EventEnvelope event = EventEnvelope.builder("Test").eventId("evt-dead").payloadJson("{}").build();
    insertEvent(event);

    dispatcher.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

    awaitStatus(event.eventId(), EventStatus.DEAD, 2_000);

    dispatcher.close();
  }

  @Test
  void inFlightPreventsConcurrentDuplicateDispatchAndAllowsLater() throws Exception {
    RecordingInFlightTracker inFlightTracker = new RecordingInFlightTracker();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();

    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .registerAll(event -> {
          int count = calls.incrementAndGet();
          if (count == 1) {
            firstStarted.countDown();
            releaseFirst.await();
          }
        });

    OutboxDispatcher dispatcher = new OutboxDispatcher(
        connectionProvider,
        repository,
        listeners,
        inFlightTracker,
        attempts -> 0L,
        10,
        0,
        10,
        10,
        MetricsExporter.NOOP
    );

    Method dispatchEvent = OutboxDispatcher.class.getDeclaredMethod("dispatchEvent", QueuedEvent.class);
    dispatchEvent.setAccessible(true);

    QueuedEvent first = new QueuedEvent(
        EventEnvelope.builder("Test").eventId("dup").payloadJson("{}").build(),
        QueuedEvent.Source.HOT,
        0
    );
    QueuedEvent second = new QueuedEvent(
        EventEnvelope.builder("Test").eventId("dup").payloadJson("{}").build(),
        QueuedEvent.Source.HOT,
        0
    );

    AtomicReference<Throwable> dispatchError = new AtomicReference<>();
    Thread t1 = new Thread(() -> invokeDispatch(dispatchEvent, dispatcher, first, dispatchError));
    Thread t2 = new Thread(() -> invokeDispatch(dispatchEvent, dispatcher, second, dispatchError));

    t1.start();
    assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

    t2.start();
    t2.join(1000);

    assertEquals(1, calls.get());
    assertTrue(inFlightTracker.failedCount() > 0);

    releaseFirst.countDown();
    t1.join(1000);

    QueuedEvent third = new QueuedEvent(
        EventEnvelope.builder("Test").eventId("dup").payloadJson("{}").build(),
        QueuedEvent.Source.HOT,
        0
    );
    invokeDispatch(dispatchEvent, dispatcher, third, dispatchError);

    if (dispatchError.get() != null) {
      throw new AssertionError("Dispatch failed", dispatchError.get());
    }

    assertEquals(2, calls.get());

    dispatcher.close();
  }

  private void insertEvent(EventEnvelope event) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      repository.insertNew(conn, event);
    }
  }

  private int getStatus(String eventId) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT status FROM outbox_event WHERE event_id=?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return -1;
        }
        return rs.getInt(1);
      }
    }
  }

  private void awaitStatus(String eventId, EventStatus status, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (getStatus(eventId) == status.code()) {
        return;
      }
      Thread.sleep(20);
    }
    assertEquals(status.code(), getStatus(eventId));
  }

  private Thread startWorker(OutboxDispatcher dispatcher, AtomicReference<Throwable> errorRef) throws Exception {
    Method workerLoop = OutboxDispatcher.class.getDeclaredMethod("workerLoop");
    workerLoop.setAccessible(true);
    Thread worker = new Thread(() -> {
      try {
        workerLoop.invoke(dispatcher);
      } catch (Throwable t) {
        errorRef.compareAndSet(null, t.getCause() == null ? t : t.getCause());
      }
    });
    worker.setDaemon(true);
    worker.start();
    return worker;
  }

  private void invokeDispatch(Method dispatchEvent, OutboxDispatcher dispatcher, QueuedEvent event,
                              AtomicReference<Throwable> errorRef) {
    try {
      dispatchEvent.invoke(dispatcher, event);
    } catch (Throwable t) {
      errorRef.compareAndSet(null, t.getCause() == null ? t : t.getCause());
    }
  }

  private void createSchema(Connection conn) throws SQLException {
    conn.createStatement().execute(
        "CREATE TABLE outbox_event (" +
            "event_id VARCHAR(36) PRIMARY KEY," +
            "event_type VARCHAR(128) NOT NULL," +
            "aggregate_type VARCHAR(64)," +
            "aggregate_id VARCHAR(128)," +
            "tenant_id VARCHAR(64)," +
            "payload CLOB NOT NULL," +
            "headers CLOB," +
            "status TINYINT NOT NULL," +
            "attempts INT NOT NULL DEFAULT 0," +
            "available_at TIMESTAMP NOT NULL," +
            "created_at TIMESTAMP NOT NULL," +
            "done_at TIMESTAMP," +
            "last_error CLOB" +
            ")"
    );
    conn.createStatement().execute(
        "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)"
    );
  }

  private static final class RecordingInFlightTracker implements InFlightTracker {
    private final DefaultInFlightTracker delegate = new DefaultInFlightTracker();
    private final AtomicInteger failed = new AtomicInteger();

    @Override
    public boolean tryAcquire(String eventId) {
      boolean acquired = delegate.tryAcquire(eventId);
      if (!acquired) {
        failed.incrementAndGet();
      }
      return acquired;
    }

    @Override
    public void release(String eventId) {
      delegate.release(eventId);
    }

    private int failedCount() {
      return failed.get();
    }
  }
}

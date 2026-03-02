package io.outbox.testing;

import io.outbox.EventEnvelope;
import io.outbox.model.EventStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OutboxTestSupportTest {

  @Test
  void writeSingleEvent() {
    var test = OutboxTestSupport.create().build();

    String id = test.writer().write(EventEnvelope.ofJson("OrderPlaced", "{\"orderId\":1}"));

    assertNotNull(id);
    assertEquals(1, test.store().size());
    assertEquals(EventStatus.NEW, test.store().statusOf(id));
  }

  @Test
  void writeMultipleEvents() {
    var test = OutboxTestSupport.create().build();

    var ids = test.writer().writeAll(java.util.List.of(
        EventEnvelope.ofJson("A", "{}"),
        EventEnvelope.ofJson("B", "{}"),
        EventEnvelope.ofJson("C", "{}")
    ));

    assertEquals(3, ids.size());
    assertEquals(3, test.store().size());
  }

  @Test
  void writerHookReceivesCallbacks() {
    var hook = new RecordingWriterHook();
    var test = OutboxTestSupport.create()
        .writerHook(hook)
        .build();

    test.writer().write(EventEnvelope.ofJson("Test", "{}"));
    test.txContext().runAfterCommit();

    assertEquals(1, hook.beforeWriteCount());
    assertEquals(1, hook.afterWriteCount());
    assertEquals(1, hook.afterCommitCount());
  }

  @Test
  void txContextTracksCallbacks() {
    var test = OutboxTestSupport.create()
        .writerHook(new RecordingWriterHook())
        .build();

    test.writer().write(EventEnvelope.ofJson("Test", "{}"));
    assertEquals(1, test.txContext().afterCommitCount());
    assertEquals(1, test.txContext().afterRollbackCount());

    test.txContext().runAfterCommit();
    assertEquals(0, test.txContext().afterCommitCount());
  }

  @Test
  void registerListenerWithEventType() {
    AtomicInteger callCount = new AtomicInteger();
    var test = OutboxTestSupport.create()
        .register("OrderPlaced", event -> callCount.incrementAndGet())
        .build();

    assertNotNull(test.listenerRegistry().listenerFor("__GLOBAL__", "OrderPlaced"));
  }

  @Test
  void registerListenerWithAggregateScope() {
    AtomicInteger callCount = new AtomicInteger();
    var test = OutboxTestSupport.create()
        .register("Order", "OrderPlaced", event -> callCount.incrementAndGet())
        .build();

    assertNotNull(test.listenerRegistry().listenerFor("Order", "OrderPlaced"));
  }
}

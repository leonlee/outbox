package io.outbox.testing;

import io.outbox.EventEnvelope;
import io.outbox.model.EventStatus;
import io.outbox.model.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InMemoryOutboxStoreTest {

  private InMemoryOutboxStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryOutboxStore();
  }

  @Test
  void insertAndRetrieve() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);

    assertEquals(1, store.size());
    assertEquals(EventStatus.NEW, store.statusOf(event.eventId()));
  }

  @Test
  void insertBatch() {
    List<EventEnvelope> events = List.of(
        EventEnvelope.ofJson("A", "{}"),
        EventEnvelope.ofJson("B", "{}"),
        EventEnvelope.ofJson("C", "{}")
    );
    store.insertBatch(null, events);

    assertEquals(3, store.size());
  }

  @Test
  void markDone() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);

    int updated = store.markDone(null, event.eventId());
    assertEquals(1, updated);
    assertEquals(EventStatus.DONE, store.statusOf(event.eventId()));
  }

  @Test
  void markRetryIncrementsAttempts() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);

    store.markRetry(null, event.eventId(), Instant.now().plusSeconds(60), "error");
    assertEquals(EventStatus.RETRY, store.statusOf(event.eventId()));

    List<OutboxEvent> all = store.all();
    assertEquals(1, all.get(0).attempts());
  }

  @Test
  void markDead() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);

    store.markDead(null, event.eventId(), "permanent failure");
    assertEquals(EventStatus.DEAD, store.statusOf(event.eventId()));
  }

  @Test
  void markDeferred() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);

    Instant nextAt = Instant.now().plusSeconds(30);
    store.markDeferred(null, event.eventId(), nextAt);

    // Should reset to NEW without incrementing attempts
    assertEquals(EventStatus.NEW, store.statusOf(event.eventId()));
    assertEquals(0, store.all().get(0).attempts());
  }

  @Test
  void pollPendingReturnsNewEvents() {
    store.insertNew(null, EventEnvelope.ofJson("A", "{}"));
    store.insertNew(null, EventEnvelope.ofJson("B", "{}"));

    List<OutboxEvent> pending = store.pollPending(null, Instant.now(), Duration.ZERO, 10);
    assertEquals(2, pending.size());
  }

  @Test
  void pollPendingExcludesDone() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);
    store.markDone(null, event.eventId());

    List<OutboxEvent> pending = store.pollPending(null, Instant.now(), Duration.ZERO, 10);
    assertEquals(0, pending.size());
  }

  @Test
  void queryDeadFiltersByType() {
    EventEnvelope a = EventEnvelope.ofJson("TypeA", "{}");
    EventEnvelope b = EventEnvelope.ofJson("TypeB", "{}");
    store.insertNew(null, a);
    store.insertNew(null, b);
    store.markDead(null, a.eventId(), "err");
    store.markDead(null, b.eventId(), "err");

    List<OutboxEvent> dead = store.queryDead(null, "TypeA", null, 10);
    assertEquals(1, dead.size());
    assertEquals("TypeA", dead.get(0).eventType());
  }

  @Test
  void countDead() {
    EventEnvelope a = EventEnvelope.ofJson("Test", "{}");
    EventEnvelope b = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, a);
    store.insertNew(null, b);
    store.markDead(null, a.eventId(), "err");

    assertEquals(1, store.countDead(null, null));
    assertEquals(1, store.countDead(null, "Test"));
    assertEquals(0, store.countDead(null, "Other"));
  }

  @Test
  void replayDead() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);
    store.markRetry(null, event.eventId(), Instant.now(), "err");
    store.markDead(null, event.eventId(), "permanent");

    int updated = store.replayDead(null, event.eventId());
    assertEquals(1, updated);
    assertEquals(EventStatus.NEW, store.statusOf(event.eventId()));
    assertEquals(0, store.all().get(0).attempts());
  }

  @Test
  void replayDeadIgnoresNonDead() {
    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    store.insertNew(null, event);

    assertEquals(0, store.replayDead(null, event.eventId()));
  }

  @Test
  void markNonExistentEventReturnsZero() {
    assertEquals(0, store.markDone(null, "nonexistent"));
    assertEquals(0, store.markRetry(null, "nonexistent", Instant.now(), "err"));
    assertEquals(0, store.markDead(null, "nonexistent", "err"));
    assertEquals(0, store.markDeferred(null, "nonexistent", Instant.now()));
    assertEquals(0, store.replayDead(null, "nonexistent"));
  }

  @Test
  void statusOfReturnsNullForUnknown() {
    assertNull(store.statusOf("nonexistent"));
  }

  @Test
  void clearRemovesAll() {
    store.insertNew(null, EventEnvelope.ofJson("A", "{}"));
    store.insertNew(null, EventEnvelope.ofJson("B", "{}"));
    assertEquals(2, store.size());

    store.clear();
    assertEquals(0, store.size());
  }
}

package io.outbox;

import org.junit.jupiter.api.Test;
import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void writeSingleDelegatesToWriteAll() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        writer.write(EventEnvelope.ofJson("Test", "{}"));

        // beforeWrite should see a single-element list
        assertEquals(1, hook.beforeWriteEvents.size());
        assertEquals(1, hook.beforeWriteEvents.get(0).size());
        assertEquals(1, store.batchInsertCount.get());
    }

    @Test
    void writerHookAfterCommitReceivesBatch() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        List<EventEnvelope> events = List.of(
                EventEnvelope.ofJson("A", "{}"),
                EventEnvelope.ofJson("B", "{}")
        );
        writer.writeAll(events);

        assertDoesNotThrow(txContext::runAfterCommit);
        assertEquals(1, hook.afterCommitCalls.get());
        assertEquals(2, hook.afterCommitEvents.get().size());
    }

    @Test
    void writerHookAfterRollbackReceivesBatch() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        writer.writeAll(List.of(EventEnvelope.ofJson("A", "{}")));

        assertDoesNotThrow(txContext::runAfterRollback);
        assertEquals(1, hook.afterRollbackCalls.get());
        assertEquals(1, hook.afterRollbackEvents.get().size());
    }

    @Test
    void writeAllCallsBeforeWriteWithBatch() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        List<EventEnvelope> events = List.of(
                EventEnvelope.ofJson("A", "{}"),
                EventEnvelope.ofJson("B", "{}"),
                EventEnvelope.ofJson("C", "{}")
        );
        writer.writeAll(events);

        assertEquals(1, hook.beforeWriteEvents.size());
        assertEquals(3, hook.beforeWriteEvents.get(0).size());
    }

    @Test
    void writeAllCallsAfterWriteWithInsertedEvents() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        List<EventEnvelope> events = List.of(
                EventEnvelope.ofJson("A", "{}"),
                EventEnvelope.ofJson("B", "{}")
        );
        writer.writeAll(events);

        assertEquals(1, hook.afterWriteCalls.get());
        assertEquals(2, hook.afterWriteEvents.get().size());
        assertEquals("A", hook.afterWriteEvents.get().get(0).eventType());
        assertEquals("B", hook.afterWriteEvents.get().get(1).eventType());
    }

    @Test
    void writeAllBeforeWriteCanTransform() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        EventEnvelope replacement = EventEnvelope.ofJson("Replaced", "{}");
        WriterHook transformHook = new WriterHook() {
            @Override
            public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
                return List.of(replacement);
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, transformHook);

        List<String> ids = writer.writeAll(List.of(
                EventEnvelope.ofJson("Original1", "{}"),
                EventEnvelope.ofJson("Original2", "{}")
        ));

        assertEquals(1, ids.size());
        assertEquals(replacement.eventId(), ids.get(0));
        assertEquals(1, store.batchInsertCount.get());
        assertEquals(1, store.lastBatch.size());
        assertEquals("Replaced", store.lastBatch.get(0).eventType());
    }

    @Test
    void writeAllBeforeWriteThrowsAborts() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook throwingHook = new WriterHook() {
            @Override
            public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
                throw new RuntimeException("abort");
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, throwingHook);

        assertThrows(RuntimeException.class, () ->
                writer.writeAll(List.of(EventEnvelope.ofJson("Test", "{}"))));
        assertEquals(0, store.batchInsertCount.get());
    }

    @Test
    void writeSingleReturnsNullWhenSuppressed() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook suppressHook = new WriterHook() {
            @Override
            public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
                return null;
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, suppressHook);

        String id = writer.write(EventEnvelope.ofJson("Test", "{}"));
        assertNull(id);
        assertEquals(0, store.batchInsertCount.get());
    }

    @Test
    void writeAllBeforeWriteReturnsNullSuppressesWrite() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook nullHook = new WriterHook() {
            @Override
            public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
                return null;
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, nullHook);

        List<String> ids = writer.writeAll(List.of(EventEnvelope.ofJson("Test", "{}")));
        assertTrue(ids.isEmpty());
        assertEquals(0, store.batchInsertCount.get());
    }

    @Test
    void writeAllBeforeWriteReturnsEmptySuppressesWrite() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook emptyHook = new WriterHook() {
            @Override
            public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
                return List.of();
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, emptyHook);

        List<String> ids = writer.writeAll(List.of(EventEnvelope.ofJson("Test", "{}")));
        assertTrue(ids.isEmpty());
        assertEquals(0, store.batchInsertCount.get());
    }

    @Test
    void writeAllAfterWriteExceptionSwallowed() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook throwingAfterWrite = new WriterHook() {
            @Override
            public void afterWrite(List<EventEnvelope> events) {
                throw new RuntimeException("boom");
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, throwingAfterWrite);

        List<String> ids = assertDoesNotThrow(() ->
                writer.writeAll(List.of(EventEnvelope.ofJson("Test", "{}"))));
        assertEquals(1, ids.size());
        assertEquals(1, store.batchInsertCount.get());
    }

    @Test
    void writeWithStringEventTypeAndPayload() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        OutboxWriter writer = new OutboxWriter(txContext, store);

        String eventId = writer.write("UserCreated", "{\"id\":1}");
        assertNotNull(eventId);
        assertEquals(1, store.batchInsertCount.get());
    }

    @Test
    void writeWithTypedEventTypeAndPayload() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        OutboxWriter writer = new OutboxWriter(txContext, store);

        EventType eventType = StringEventType.of("OrderPlaced");
        String eventId = writer.write(eventType, "{\"orderId\":1}");
        assertNotNull(eventId);
        assertEquals(1, store.batchInsertCount.get());
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
        assertEquals(1, store.batchInsertCount.get());
        assertEquals(3, store.lastBatch.size());
    }

    @Test
    void writeAllThrowsWhenNoActiveTransaction() {
        StubTxContext txContext = new StubTxContext(false);
        RecordingOutboxStore store = new RecordingOutboxStore();

        OutboxWriter writer = new OutboxWriter(txContext, store);

        assertThrows(IllegalStateException.class, () ->
                writer.writeAll(List.of(EventEnvelope.ofJson("Test", "{}"))));
    }

    @Test
    void writeAllEmptyListReturnsEmpty() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        List<String> ids = writer.writeAll(List.of());
        assertTrue(ids.isEmpty());
        assertEquals(0, store.batchInsertCount.get());
        assertEquals(0, hook.beforeWriteEvents.size());
    }

    @Test
    void multipleWritesInSameTransactionFireAllCallbacks() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();
        RecordingHook hook = new RecordingHook();

        OutboxWriter writer = new OutboxWriter(txContext, store, hook);

        writer.write(EventEnvelope.ofJson("A", "{}"));
        writer.write(EventEnvelope.ofJson("B", "{}"));

        assertEquals(2, txContext.afterCommitCallbacks.size());
        assertEquals(2, txContext.afterRollbackCallbacks.size());

        // Fire all afterCommit callbacks
        txContext.runAfterCommit();
        assertEquals(2, hook.afterCommitCalls.get());
    }

    @Test
    void afterCommitExceptionSwallowed() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook throwingHook = new WriterHook() {
            @Override
            public void afterCommit(List<EventEnvelope> events) {
                throw new RuntimeException("boom");
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, throwingHook);
        writer.write(EventEnvelope.ofJson("Test", "{}"));

        assertDoesNotThrow(txContext::runAfterCommit);
    }

    @Test
    void afterRollbackExceptionSwallowed() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        WriterHook throwingHook = new WriterHook() {
            @Override
            public void afterRollback(List<EventEnvelope> events) {
                throw new RuntimeException("boom");
            }
        };

        OutboxWriter writer = new OutboxWriter(txContext, store, throwingHook);
        writer.write(EventEnvelope.ofJson("Test", "{}"));

        assertDoesNotThrow(txContext::runAfterRollback);
    }

    @Test
    void noopHookDoesNotRegisterCallbacks() {
        StubTxContext txContext = new StubTxContext(true);
        RecordingOutboxStore store = new RecordingOutboxStore();

        OutboxWriter writer = new OutboxWriter(txContext, store);

        writer.write(EventEnvelope.ofJson("Test", "{}"));

        // With NOOP hook, no afterCommit/afterRollback should be registered
        assertEquals(0, txContext.afterCommitCallbacks.size());
        assertEquals(0, txContext.afterRollbackCallbacks.size());
    }

    private static final class StubTxContext implements TxContext {
        private final boolean active;
        final List<Runnable> afterCommitCallbacks = new ArrayList<>();
        final List<Runnable> afterRollbackCallbacks = new ArrayList<>();

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
            afterCommitCallbacks.add(callback);
        }

        @Override
        public void afterRollback(Runnable callback) {
            afterRollbackCallbacks.add(callback);
        }

        private void runAfterCommit() {
            for (Runnable callback : afterCommitCallbacks) {
                callback.run();
            }
        }

        private void runAfterRollback() {
            for (Runnable callback : afterRollbackCallbacks) {
                callback.run();
            }
        }
    }

    private static final class RecordingOutboxStore implements OutboxStore {
        private final AtomicInteger batchInsertCount = new AtomicInteger();
        private volatile List<EventEnvelope> lastBatch = List.of();

        @Override
        public void insertNew(Connection conn, EventEnvelope event) {
            batchInsertCount.incrementAndGet();
            lastBatch = List.of(event);
        }

        @Override
        public void insertBatch(Connection conn, List<EventEnvelope> events) {
            batchInsertCount.incrementAndGet();
            lastBatch = List.copyOf(events);
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
        public List<io.outbox.model.OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class RecordingHook implements WriterHook {
        private final List<List<EventEnvelope>> beforeWriteEvents = new ArrayList<>();
        private final AtomicInteger afterWriteCalls = new AtomicInteger();
        private final AtomicReference<List<EventEnvelope>> afterWriteEvents = new AtomicReference<>();
        private final AtomicInteger afterCommitCalls = new AtomicInteger();
        private final AtomicReference<List<EventEnvelope>> afterCommitEvents = new AtomicReference<>();
        private final AtomicInteger afterRollbackCalls = new AtomicInteger();
        private final AtomicReference<List<EventEnvelope>> afterRollbackEvents = new AtomicReference<>();

        @Override
        public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
            beforeWriteEvents.add(List.copyOf(events));
            return events;
        }

        @Override
        public void afterWrite(List<EventEnvelope> events) {
            afterWriteCalls.incrementAndGet();
            afterWriteEvents.set(List.copyOf(events));
        }

        @Override
        public void afterCommit(List<EventEnvelope> events) {
            afterCommitCalls.incrementAndGet();
            afterCommitEvents.set(List.copyOf(events));
        }

        @Override
        public void afterRollback(List<EventEnvelope> events) {
            afterRollbackCalls.incrementAndGet();
            afterRollbackEvents.set(List.copyOf(events));
        }
    }
}

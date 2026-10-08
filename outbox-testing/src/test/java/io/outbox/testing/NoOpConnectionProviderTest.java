package io.outbox.testing;

import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.dispatch.OutboxDispatcher;
import io.outbox.dispatch.QueuedEvent;
import io.outbox.model.EventStatus;
import io.outbox.registry.DefaultListenerRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NoOpConnectionProviderTest {

    @Test
    void connectionAcceptsTheCallsTheDispatcherMakes() {
        Connection conn = new NoOpConnectionProvider().getConnection();

        assertNotNull(conn);
        assertDoesNotThrow(() -> {
            conn.setAutoCommit(false);
            conn.commit();
            conn.rollback();
            conn.close();
        });
    }

    /**
     * The documented use: an in-memory store under a real dispatcher. With a null connection the
     * dispatcher's setAutoCommit failed, the status update was logged and dropped, and the event
     * never left NEW.
     */
    @Test
    void dispatcherMarksInMemoryEventsDone() throws Exception {
        var store = new InMemoryOutboxStore();
        var registry = new DefaultListenerRegistry().register("Ping", event -> DispatchResult.done());
        EventEnvelope event = EventEnvelope.ofJson("Ping", "{}");
        store.insertNew(null, event);

        try (var dispatcher = OutboxDispatcher.builder()
                .connectionProvider(new NoOpConnectionProvider())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .build()) {
            dispatcher.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

            long deadline = System.currentTimeMillis() + 5_000;
            while (store.statusOf(event.eventId()) != EventStatus.DONE && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(EventStatus.DONE, store.statusOf(event.eventId()));
        }
    }
}

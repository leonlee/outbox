package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.registry.DefaultListenerRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherPollerHandlerTest {

  @Test
  void constructorRejectsNullDispatcher() {
    assertThrows(NullPointerException.class, () -> new DispatcherPollerHandler(null));
  }

  @Test
  void handleEnqueuesToColdQueue() {
    var dispatcher = newDispatcher(0, 10);
    var handler = new DispatcherPollerHandler(dispatcher);

    EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
    boolean result = handler.handle(event, 2);

    assertTrue(result);
  }

  @Test
  void handleReturnsFalseWhenColdQueueFull() {
    var dispatcher = newDispatcher(0, 1); // cold capacity=1
    var handler = new DispatcherPollerHandler(dispatcher);

    handler.handle(EventEnvelope.ofJson("A", "{}"), 0);
    boolean result = handler.handle(EventEnvelope.ofJson("B", "{}"), 0);

    assertFalse(result);
  }

  @Test
  void availableCapacityDelegatesToDispatcher() {
    var dispatcher = newDispatcher(0, 5);
    var handler = new DispatcherPollerHandler(dispatcher);

    assertEquals(5, handler.availableCapacity());

    handler.handle(EventEnvelope.ofJson("A", "{}"), 0);

    assertEquals(4, handler.availableCapacity());
  }

  private static OutboxDispatcher newDispatcher(int workerCount, int coldCapacity) {
    return OutboxDispatcher.builder()
        .connectionProvider(() -> { throw new UnsupportedOperationException(); })
        .outboxStore(new StubOutboxStore())
        .listenerRegistry(new DefaultListenerRegistry())
        .workerCount(workerCount)
        .hotQueueCapacity(10)
        .coldQueueCapacity(coldCapacity)
        .build();
  }
}

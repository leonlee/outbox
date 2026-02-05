package outbox.registry;

import outbox.EventListener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultListenerRegistryTest {

  @Test
  void returnsEmptyListForUnregisteredEventType() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();

    List<EventListener> listeners = registry.listenersFor("Unknown");

    assertTrue(listeners.isEmpty());
  }

  @Test
  void returnsRegisteredListener() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger called = new AtomicInteger();

    registry.register("UserCreated", event -> called.incrementAndGet());

    List<EventListener> listeners = registry.listenersFor("UserCreated");
    assertEquals(1, listeners.size());

    listeners.get(0).onEvent(null);
    assertEquals(1, called.get());
  }

  @Test
  void supportsMultipleListenersForSameEventType() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger counter = new AtomicInteger();

    registry.register("OrderPlaced", event -> counter.addAndGet(1));
    registry.register("OrderPlaced", event -> counter.addAndGet(10));
    registry.register("OrderPlaced", event -> counter.addAndGet(100));

    List<EventListener> listeners = registry.listenersFor("OrderPlaced");
    assertEquals(3, listeners.size());

    for (EventListener l : listeners) {
      l.onEvent(null);
    }
    assertEquals(111, counter.get());
  }

  @Test
  void wildcardListenerAppliesToAllEventTypes() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger called = new AtomicInteger();

    registry.registerAll(event -> called.incrementAndGet());

    List<EventListener> forUser = registry.listenersFor("UserCreated");
    List<EventListener> forOrder = registry.listenersFor("OrderPlaced");
    List<EventListener> forAny = registry.listenersFor("AnyEvent");

    assertEquals(1, forUser.size());
    assertEquals(1, forOrder.size());
    assertEquals(1, forAny.size());
  }

  @Test
  void combinesSpecificAndWildcardListeners() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger specific = new AtomicInteger();
    AtomicInteger wildcard = new AtomicInteger();

    registry.register("UserCreated", event -> specific.incrementAndGet());
    registry.registerAll(event -> wildcard.incrementAndGet());

    List<EventListener> listeners = registry.listenersFor("UserCreated");
    assertEquals(2, listeners.size());

    for (EventListener l : listeners) {
      l.onEvent(null);
    }
    assertEquals(1, specific.get());
    assertEquals(1, wildcard.get());
  }

  @Test
  void returnedListIsUnmodifiable() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    registry.register("Test", event -> {});

    List<EventListener> listeners = registry.listenersFor("Test");

    assertThrows(UnsupportedOperationException.class, () ->
        listeners.add(event -> {}));
  }

  @Test
  void fluentApiSupportsChaining() {
    AtomicInteger count = new AtomicInteger();

    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("A", event -> count.incrementAndGet())
        .register("B", event -> count.incrementAndGet())
        .registerAll(event -> count.incrementAndGet());

    assertEquals(2, registry.listenersFor("A").size());
    assertEquals(2, registry.listenersFor("B").size());
    assertEquals(1, registry.listenersFor("C").size());
  }
}

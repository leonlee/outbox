package outbox.registry;

import outbox.AggregateType;
import outbox.EventListener;
import outbox.StringAggregateType;
import outbox.StringEventType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultListenerRegistryTest {

  @Test
  void returnsNullForUnregistered() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();

    assertNull(registry.listenerFor(AggregateType.GLOBAL.name(), "Unknown"));
  }

  @Test
  void returnsRegisteredListener() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger called = new AtomicInteger();

    registry.register("UserCreated", event -> called.incrementAndGet());

    EventListener listener = registry.listenerFor(AggregateType.GLOBAL.name(), "UserCreated");
    assertNotNull(listener);

    listener.onEvent(null);
    assertEquals(1, called.get());
  }

  @Test
  void duplicateRegistrationThrows() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    registry.register("UserCreated", event -> {});

    assertThrows(IllegalStateException.class, () ->
        registry.register("UserCreated", event -> {}));
  }

  @Test
  void aggregateTypeScopedRegistration() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger orderCalled = new AtomicInteger();
    AtomicInteger userCalled = new AtomicInteger();

    registry.register("Order", "Created", event -> orderCalled.incrementAndGet());
    registry.register("User", "Created", event -> userCalled.incrementAndGet());

    EventListener orderListener = registry.listenerFor("Order", "Created");
    EventListener userListener = registry.listenerFor("User", "Created");

    assertNotNull(orderListener);
    assertNotNull(userListener);

    orderListener.onEvent(null);
    userListener.onEvent(null);

    assertEquals(1, orderCalled.get());
    assertEquals(1, userCalled.get());
  }

  @Test
  void convenienceRegisterUsesGlobal() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    registry.register("UserCreated", event -> {});

    assertNotNull(registry.listenerFor(AggregateType.GLOBAL.name(), "UserCreated"));
  }

  @Test
  void registerWithTypeSafeTypes() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger called = new AtomicInteger();

    AggregateType orderType = StringAggregateType.of("Order");
    registry.register(orderType, StringEventType.of("OrderPlaced"), event -> called.incrementAndGet());

    EventListener listener = registry.listenerFor("Order", "OrderPlaced");
    assertNotNull(listener);

    listener.onEvent(null);
    assertEquals(1, called.get());
  }

  @Test
  void registerWithAggregateTypeAndStringEventType() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger called = new AtomicInteger();

    AggregateType userType = StringAggregateType.of("User");
    registry.register(userType, "UserCreated", event -> called.incrementAndGet());

    EventListener listener = registry.listenerFor("User", "UserCreated");
    assertNotNull(listener);

    listener.onEvent(null);
    assertEquals(1, called.get());
  }

  @Test
  void registerWithEventTypeInterface() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();
    AtomicInteger called = new AtomicInteger();

    registry.register(StringEventType.of("OrderPlaced"), event -> called.incrementAndGet());

    EventListener listener = registry.listenerFor(AggregateType.GLOBAL.name(), "OrderPlaced");
    assertNotNull(listener);

    listener.onEvent(null);
    assertEquals(1, called.get());
  }

  @Test
  void fluentApiSupportsChaining() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("A", event -> {})
        .register("B", event -> {});

    assertNotNull(registry.listenerFor(AggregateType.GLOBAL.name(), "A"));
    assertNotNull(registry.listenerFor(AggregateType.GLOBAL.name(), "B"));
    assertNull(registry.listenerFor(AggregateType.GLOBAL.name(), "C"));
  }

  @Test
  void registerRejectsNullAggregateType() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();

    assertThrows(NullPointerException.class, () ->
        registry.register((String) null, "E", event -> {}));
  }

  @Test
  void registerRejectsNullEventType() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();

    assertThrows(NullPointerException.class, () ->
        registry.register("A", (String) null, event -> {}));
  }

  @Test
  void registerRejectsNullListener() {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();

    assertThrows(NullPointerException.class, () ->
        registry.register("A", "E", null));
  }
}

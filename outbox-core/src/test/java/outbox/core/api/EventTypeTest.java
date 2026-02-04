package outbox.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventTypeTest {

  // Example enum implementing EventType
  enum UserEvents implements EventType {
    USER_CREATED,
    USER_UPDATED,
    USER_DELETED
  }

  enum OrderEvents implements EventType {
    ORDER_PLACED,
    ORDER_SHIPPED,
    ORDER_CANCELLED
  }

  // Example enum implementing AggregateType
  enum Aggregates implements AggregateType {
    USER,
    ORDER,
    PRODUCT
  }

  @Test
  void enumEventTypeReturnsEnumName() {
    assertEquals("USER_CREATED", UserEvents.USER_CREATED.name());
    assertEquals("ORDER_PLACED", OrderEvents.ORDER_PLACED.name());
  }

  @Test
  void stringEventTypeReturnsProvidedName() {
    StringEventType type = StringEventType.of("CustomEvent");

    assertEquals("CustomEvent", type.name());
  }

  @Test
  void stringEventTypeEquality() {
    StringEventType type1 = StringEventType.of("SameEvent");
    StringEventType type2 = StringEventType.of("SameEvent");
    StringEventType type3 = StringEventType.of("DifferentEvent");

    assertEquals(type1, type2);
    assertNotEquals(type1, type3);
    assertEquals(type1.hashCode(), type2.hashCode());
  }

  @Test
  void stringEventTypeToString() {
    StringEventType type = StringEventType.of("TestEvent");

    assertEquals("TestEvent", type.toString());
  }

  @Test
  void stringEventTypeRejectsNull() {
    assertThrows(NullPointerException.class, () -> StringEventType.of(null));
  }

  @Test
  void stringEventTypeRejectsEmpty() {
    assertThrows(IllegalArgumentException.class, () -> StringEventType.of(""));
  }

  @Test
  void eventEnvelopeWithEnumType() {
    EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
        .payloadJson("{\"userId\":1}")
        .build();

    assertEquals("USER_CREATED", envelope.eventType());
  }

  @Test
  void eventEnvelopeOfJsonWithEnumType() {
    EventEnvelope envelope = EventEnvelope.ofJson(OrderEvents.ORDER_PLACED, "{\"orderId\":\"123\"}");

    assertEquals("ORDER_PLACED", envelope.eventType());
    assertEquals("{\"orderId\":\"123\"}", envelope.payloadJson());
  }

  @Test
  void eventEnvelopeWithStringEventType() {
    EventEnvelope envelope = EventEnvelope.builder(StringEventType.of("DynamicEvent"))
        .payloadJson("{}")
        .build();

    assertEquals("DynamicEvent", envelope.eventType());
  }

  @Test
  void mixedEnumAndStringTypesInRegistry() {
    var registry = new outbox.core.registry.DefaultListenerRegistry();
    java.util.concurrent.atomic.AtomicInteger enumCalled = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger stringCalled = new java.util.concurrent.atomic.AtomicInteger();

    // Register with enum
    registry.register(UserEvents.USER_CREATED, event -> enumCalled.incrementAndGet());
    // Register with string (same underlying name)
    registry.register("USER_CREATED", event -> stringCalled.incrementAndGet());

    // Both should be found for "USER_CREATED"
    var listeners = registry.listenersFor("USER_CREATED");
    assertEquals(2, listeners.size());
  }

  // AggregateType tests

  @Test
  void enumAggregateTypeReturnsEnumName() {
    assertEquals("USER", Aggregates.USER.name());
    assertEquals("ORDER", Aggregates.ORDER.name());
  }

  @Test
  void stringAggregateTypeReturnsProvidedName() {
    StringAggregateType type = StringAggregateType.of("CustomAggregate");

    assertEquals("CustomAggregate", type.name());
  }

  @Test
  void stringAggregateTypeEquality() {
    StringAggregateType type1 = StringAggregateType.of("SameAggregate");
    StringAggregateType type2 = StringAggregateType.of("SameAggregate");
    StringAggregateType type3 = StringAggregateType.of("DifferentAggregate");

    assertEquals(type1, type2);
    assertNotEquals(type1, type3);
    assertEquals(type1.hashCode(), type2.hashCode());
  }

  @Test
  void stringAggregateTypeToString() {
    StringAggregateType type = StringAggregateType.of("TestAggregate");

    assertEquals("TestAggregate", type.toString());
  }

  @Test
  void stringAggregateTypeRejectsNull() {
    assertThrows(NullPointerException.class, () -> StringAggregateType.of(null));
  }

  @Test
  void stringAggregateTypeRejectsEmpty() {
    assertThrows(IllegalArgumentException.class, () -> StringAggregateType.of(""));
  }

  @Test
  void eventEnvelopeWithEnumAggregateType() {
    EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
        .aggregateType(Aggregates.USER)
        .aggregateId("123")
        .payloadJson("{\"userId\":1}")
        .build();

    assertEquals("USER", envelope.aggregateType());
    assertEquals("123", envelope.aggregateId());
  }

  @Test
  void eventEnvelopeWithStringAggregateType() {
    EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
        .aggregateType(StringAggregateType.of("DynamicAggregate"))
        .aggregateId("456")
        .payloadJson("{}")
        .build();

    assertEquals("DynamicAggregate", envelope.aggregateType());
  }

  @Test
  void eventEnvelopeWithBothTypeSafeEventAndAggregateTypes() {
    EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
        .aggregateType(Aggregates.USER)
        .aggregateId("123")
        .payloadJson("{\"userId\":1}")
        .build();

    assertEquals("USER_CREATED", envelope.eventType());
    assertEquals("USER", envelope.aggregateType());
  }
}

package outbox.core.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventEnvelopeTest {

  @Test
  void builderCreatesEnvelopeWithDefaults() {
    EventEnvelope envelope = EventEnvelope.builder("UserCreated")
        .payloadJson("{\"id\":1}")
        .build();

    assertNotNull(envelope.eventId());
    assertEquals(36, envelope.eventId().length()); // UUID format
    assertEquals("UserCreated", envelope.eventType());
    assertNotNull(envelope.occurredAt());
    assertNull(envelope.aggregateType());
    assertNull(envelope.aggregateId());
    assertNull(envelope.tenantId());
    assertTrue(envelope.headers().isEmpty());
    assertEquals("{\"id\":1}", envelope.payloadJson());
  }

  @Test
  void builderAcceptsAllFields() {
    Instant now = Instant.now();
    Map<String, String> headers = Map.of("key", "value");

    EventEnvelope envelope = EventEnvelope.builder("OrderPlaced")
        .eventId("custom-id")
        .occurredAt(now)
        .aggregateType("Order")
        .aggregateId("order-123")
        .tenantId("tenant-A")
        .headers(headers)
        .payloadJson("{\"amount\":99.99}")
        .build();

    assertEquals("custom-id", envelope.eventId());
    assertEquals("OrderPlaced", envelope.eventType());
    assertEquals(now, envelope.occurredAt());
    assertEquals("Order", envelope.aggregateType());
    assertEquals("order-123", envelope.aggregateId());
    assertEquals("tenant-A", envelope.tenantId());
    assertEquals("value", envelope.headers().get("key"));
    assertEquals("{\"amount\":99.99}", envelope.payloadJson());
  }

  @Test
  void ofJsonCreatesSimpleEnvelope() {
    EventEnvelope envelope = EventEnvelope.ofJson("TestEvent", "{\"test\":true}");

    assertEquals("TestEvent", envelope.eventType());
    assertEquals("{\"test\":true}", envelope.payloadJson());
  }

  @Test
  void payloadBytesConvertedToJson() {
    byte[] payload = "{\"fromBytes\":true}".getBytes(StandardCharsets.UTF_8);

    EventEnvelope envelope = EventEnvelope.builder("ByteEvent")
        .payloadBytes(payload)
        .build();

    assertEquals("{\"fromBytes\":true}", envelope.payloadJson());
    assertArrayEquals(payload, envelope.payloadBytes());
  }

  @Test
  void payloadJsonConvertedToBytes() {
    EventEnvelope envelope = EventEnvelope.ofJson("JsonEvent", "{\"fromJson\":true}");

    byte[] expected = "{\"fromJson\":true}".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, envelope.payloadBytes());
  }

  @Test
  void headersAreImmutable() {
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("key", "value");

    EventEnvelope envelope = EventEnvelope.builder("Test")
        .headers(headers)
        .payloadJson("{}")
        .build();

    assertThrows(UnsupportedOperationException.class, () ->
        envelope.headers().put("newKey", "newValue"));
  }

  @Test
  void payloadBytesAreDefensivelyCopied() {
    byte[] original = "{\"mutable\":true}".getBytes(StandardCharsets.UTF_8);

    EventEnvelope envelope = EventEnvelope.builder("Test")
        .payloadBytes(original)
        .build();

    // Modify original
    original[0] = 'X';

    // Envelope should not be affected
    assertEquals("{\"mutable\":true}", envelope.payloadJson());
  }

  @Test
  void payloadBytesReturnDefensiveCopy() {
    EventEnvelope envelope = EventEnvelope.ofJson("Test", "{\"data\":1}");

    byte[] bytes1 = envelope.payloadBytes();
    byte[] bytes2 = envelope.payloadBytes();

    assertNotSame(bytes1, bytes2);
    assertArrayEquals(bytes1, bytes2);
  }

  @Test
  void requiresPayload() {
    assertThrows(IllegalArgumentException.class, () ->
        EventEnvelope.builder("NoPayload").build());
  }

  @Test
  void rejectsBothPayloadJsonAndBytes() {
    assertThrows(IllegalArgumentException.class, () ->
        EventEnvelope.builder("BothPayloads")
            .payloadJson("{}")
            .payloadBytes("{}".getBytes())
            .build());
  }

  @Test
  void requiresEventType() {
    assertThrows(NullPointerException.class, () ->
        EventEnvelope.builder((String) null).payloadJson("{}").build());
  }

  @Test
  void rejectsOversizedPayload() {
    byte[] largePayload = new byte[EventEnvelope.MAX_PAYLOAD_BYTES + 1];
    java.util.Arrays.fill(largePayload, (byte) 'x');

    assertThrows(IllegalArgumentException.class, () ->
        EventEnvelope.builder("Large")
            .payloadBytes(largePayload)
            .build());
  }

  @Test
  void rejectsOversizedJsonPayload() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < EventEnvelope.MAX_PAYLOAD_BYTES + 100; i++) {
      sb.append('x');
    }

    assertThrows(IllegalArgumentException.class, () ->
        EventEnvelope.builder("Large")
            .payloadJson(sb.toString())
            .build());
  }

  @Test
  void acceptsMaxSizePayload() {
    byte[] maxPayload = new byte[EventEnvelope.MAX_PAYLOAD_BYTES];
    java.util.Arrays.fill(maxPayload, (byte) 'x');

    EventEnvelope envelope = EventEnvelope.builder("MaxSize")
        .payloadBytes(maxPayload)
        .build();

    assertEquals(EventEnvelope.MAX_PAYLOAD_BYTES, envelope.payloadBytes().length);
  }
}

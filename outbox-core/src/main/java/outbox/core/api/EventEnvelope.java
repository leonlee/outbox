package outbox.core.api;

import com.github.f4b6a3.ulid.UlidCreator;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EventEnvelope {
  public static final int MAX_PAYLOAD_BYTES = 1024 * 1024; // 1MB

  private final String eventId;
  private final String eventType;
  private final Instant occurredAt;
  private final String aggregateType;
  private final String aggregateId;
  private final String tenantId;
  private final Map<String, String> headers;
  private final String payloadJson;
  private final byte[] payloadBytes;

  private EventEnvelope(Builder builder) {
    this.eventId = builder.eventId == null ? newEventId() : builder.eventId;
    this.eventType = Objects.requireNonNull(builder.eventType, "eventType");
    this.occurredAt = builder.occurredAt == null ? Instant.now() : builder.occurredAt;
    this.aggregateType = builder.aggregateType;
    this.aggregateId = builder.aggregateId;
    this.tenantId = builder.tenantId;

    Map<String, String> headerCopy = builder.headers == null
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    this.headers = headerCopy;

    if (builder.payloadJson == null && builder.payloadBytes == null) {
      throw new IllegalArgumentException("payloadJson or payloadBytes must be set");
    }
    if (builder.payloadJson != null && builder.payloadBytes != null) {
      throw new IllegalArgumentException("Set either payloadJson or payloadBytes, not both");
    }

    if (builder.payloadJson != null) {
      this.payloadJson = builder.payloadJson;
      byte[] bytes = builder.payloadJson.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_PAYLOAD_BYTES) {
        throw new IllegalArgumentException("Payload exceeds maximum size of " + MAX_PAYLOAD_BYTES + " bytes");
      }
      this.payloadBytes = Arrays.copyOf(bytes, bytes.length);
    } else {
      if (builder.payloadBytes.length > MAX_PAYLOAD_BYTES) {
        throw new IllegalArgumentException("Payload exceeds maximum size of " + MAX_PAYLOAD_BYTES + " bytes");
      }
      this.payloadBytes = Arrays.copyOf(builder.payloadBytes, builder.payloadBytes.length);
      this.payloadJson = new String(this.payloadBytes, StandardCharsets.UTF_8);
    }
  }

  /**
   * Creates a builder with a type-safe event type.
   *
   * @param eventType the event type (enum or other EventType implementation)
   * @return a new builder
   */
  public static Builder builder(EventType eventType) {
    return new Builder(eventType.name());
  }

  /**
   * Creates a builder with a string event type.
   *
   * @param eventType the event type name
   * @return a new builder
   */
  public static Builder builder(String eventType) {
    return new Builder(eventType);
  }

  /**
   * Creates an envelope with a type-safe event type and JSON payload.
   *
   * @param eventType the event type
   * @param payloadJson the JSON payload
   * @return a new envelope
   */
  public static EventEnvelope ofJson(EventType eventType, String payloadJson) {
    return builder(eventType).payloadJson(payloadJson).build();
  }

  /**
   * Creates an envelope with a string event type and JSON payload.
   *
   * @param eventType the event type name
   * @param payloadJson the JSON payload
   * @return a new envelope
   */
  public static EventEnvelope ofJson(String eventType, String payloadJson) {
    return builder(eventType).payloadJson(payloadJson).build();
  }

  public String eventId() {
    return eventId;
  }

  public String eventType() {
    return eventType;
  }

  public Instant occurredAt() {
    return occurredAt;
  }

  public String aggregateType() {
    return aggregateType;
  }

  public String aggregateId() {
    return aggregateId;
  }

  public String tenantId() {
    return tenantId;
  }

  public Map<String, String> headers() {
    return headers;
  }

  public String payloadJson() {
    return payloadJson;
  }

  public byte[] payloadBytes() {
    return Arrays.copyOf(payloadBytes, payloadBytes.length);
  }

  public static final class Builder {
    private final String eventType;
    private String eventId;
    private Instant occurredAt;
    private String aggregateType;
    private String aggregateId;
    private String tenantId;
    private Map<String, String> headers;
    private String payloadJson;
    private byte[] payloadBytes;

    private Builder(String eventType) {
      this.eventType = eventType;
    }

    public Builder eventId(String eventId) {
      this.eventId = eventId;
      return this;
    }

    public Builder occurredAt(Instant occurredAt) {
      this.occurredAt = occurredAt;
      return this;
    }

    public Builder aggregateType(String aggregateType) {
      this.aggregateType = aggregateType;
      return this;
    }

    public Builder aggregateType(AggregateType aggregateType) {
      this.aggregateType = aggregateType.name();
      return this;
    }

    public Builder aggregateId(String aggregateId) {
      this.aggregateId = aggregateId;
      return this;
    }

    public Builder tenantId(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder headers(Map<String, String> headers) {
      this.headers = headers;
      return this;
    }

    public Builder payloadJson(String payloadJson) {
      this.payloadJson = payloadJson;
      return this;
    }

    public Builder payloadBytes(byte[] payloadBytes) {
      this.payloadBytes = payloadBytes;
      return this;
    }

    public EventEnvelope build() {
      return new EventEnvelope(this);
    }
  }

  private static String newEventId() {
    return UlidCreator.getMonotonicUlid().toString();
  }
}

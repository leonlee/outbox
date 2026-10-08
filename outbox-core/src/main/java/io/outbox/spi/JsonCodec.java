package io.outbox.spi;

import java.util.Collections;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Codec for JSON serialization/deserialization used by the outbox framework.
 *
 * <p>Implementations must provide {@link #toJson(Object)} and
 * {@link #fromJson(String, Class)} for payload and header encoding.
 * A convenience {@link #parseStringMap(String)} default method is provided
 * for header decoding with null-safety.
 *
 * <h2>Resolution Order</h2>
 * <ol>
 *   <li>Programmatic override via {@link #setDefault(JsonCodec)}</li>
 *   <li>{@link ServiceLoader} — exactly one provider from
 *       {@code META-INF/services/io.outbox.spi.JsonCodec}</li>
 * </ol>
 *
 * <p>If no implementation is found, {@link #getDefault()} throws {@link IllegalStateException}.
 * Add {@code outbox-gson} to the classpath for a lightweight Gson-based codec, or
 * call {@link #setDefault(JsonCodec)} with a custom implementation (e.g.
 * {@code JacksonJsonCodec} in Spring Boot).
 *
 * @see #getDefault()
 * @see #setDefault(JsonCodec)
 */
public interface JsonCodec {
    /**
     * Returns the default implementation, resolved via programmatic override
     * or {@link ServiceLoader} discovery.
     *
     * @return the default {@link JsonCodec}
     * @throws IllegalStateException if no implementation is available
     */
    static JsonCodec getDefault() {
        return JsonCodecHolder.get();
    }

    /**
     * Sets the default implementation programmatically, overriding SPI discovery.
     *
     * <p>Typically called by framework integrations (e.g. Spring Boot auto-configuration)
     * that manage the codec lifecycle externally.
     *
     * @param codec the codec to use as default
     */
    static void setDefault(JsonCodec codec) {
        JsonCodecHolder.set(codec);
    }

    /**
     * Resets the cached default instance, allowing re-discovery or re-set.
     * Intended for testing only.
     */
    static void resetDefault() {
        JsonCodecHolder.reset();
    }

    /**
     * Serializes an object to a JSON string.
     *
     * @param obj the object to serialize
     * @return the JSON string
     * @throws io.outbox.PayloadParseException if the object cannot be serialized
     */
    String toJson(Object obj);

    /**
     * Deserializes a JSON string into an object of the given type.
     *
     * @param json the JSON string
     * @param type the target class
     * @param <T>  the target type
     * @return the deserialized object
     */
    <T> T fromJson(String json, Class<T> type);

    /**
     * Parses a JSON object string into a string map.
     * Returns an empty map for {@code null}, blank, or {@code "null"} input.
     *
     * <p>Default implementation delegates to {@link #fromJson(String, Class)}.
     * Implementations may override for type-safe generic deserialization.
     *
     * @param json the JSON string to parse
     * @return parsed map (never {@code null})
     */
    @SuppressWarnings("unchecked")
    default Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return Collections.emptyMap();
        }
        Map<String, String> result = (Map<String, String>) fromJson(json, Map.class);
        return result == null ? Collections.emptyMap() : result;
    }
}

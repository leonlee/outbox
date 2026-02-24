package outbox.util;

import java.util.Map;

/**
 * Codec for {@code Map<String, String>} header maps to/from JSON.
 *
 * <p>The default implementation ({@link DefaultJsonCodec}) is a lightweight,
 * zero-dependency encoder/decoder that only supports flat string-to-string objects.
 * Users who already have Jackson, Gson, or another JSON library on the classpath
 * can implement this interface to delegate to their preferred library.
 *
 * @see #getDefault()
 * @see DefaultJsonCodec
 */
public interface JsonCodec {

    /**
     * Returns the default singleton implementation.
     *
     * @return the default {@link JsonCodec}
     */
    static JsonCodec getDefault() {
        return DefaultJsonCodec.INSTANCE;
    }

    /**
     * Encodes a string map as a JSON object string. Returns {@code null} if the map is null or empty.
     *
     * @param headers the headers to encode
     * @return JSON string, or {@code null}
     */
    String toJson(Map<String, String> headers);

    /**
     * Parses a JSON object string into a string map. Returns an empty map for {@code null},
     * empty, or {@code "null"} input.
     *
     * @param json the JSON string to parse
     * @return parsed map (never {@code null})
     * @throws IllegalArgumentException if the input is not a valid JSON object
     */
    Map<String, String> parseObject(String json);
}

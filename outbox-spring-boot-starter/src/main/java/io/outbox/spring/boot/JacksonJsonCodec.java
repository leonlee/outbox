package io.outbox.spring.boot;

import io.outbox.PayloadParseException;
import io.outbox.spi.JsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

/**
 * {@link JsonCodec} implementation backed by a Jackson 3 {@link ObjectMapper}.
 *
 * <p>Auto-configured by {@link OutboxAutoConfiguration} when Jackson is on the classpath,
 * overriding any SPI-discovered codec (e.g. Gson).
 */
public final class JacksonJsonCodec implements JsonCodec {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JacksonJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            throw new PayloadParseException("Failed to serialize object to JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new PayloadParseException("Failed to deserialize JSON to " + type.getSimpleName(), e);
        }
    }

    @Override
    public Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> result = objectMapper.readValue(json, MAP_TYPE);
            return result == null ? Collections.emptyMap() : result;
        } catch (JacksonException e) {
            throw new PayloadParseException("Failed to parse headers JSON", e);
        }
    }
}

package io.outbox.spring.boot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.outbox.PayloadParseException;
import io.outbox.spi.JsonCodec;

import java.util.Collections;
import java.util.Map;

/**
 * {@link JsonCodec} implementation backed by Jackson {@link ObjectMapper}.
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
        } catch (JsonProcessingException e) {
            throw new PayloadParseException("Failed to serialize object to JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
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
        } catch (JsonProcessingException e) {
            throw new PayloadParseException("Failed to parse headers JSON", e);
        }
    }
}

package io.outbox.gson;

import io.outbox.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonJsonCodecTest {

    private final GsonJsonCodec codec = new GsonJsonCodec();

    @Test
    void toJsonAndParseStringMap() {
        String json = codec.toJson(Map.of("a", "1", "b", "2"));
        assertNotNull(json);
        Map<String, String> parsed = codec.parseStringMap(json);
        assertEquals("1", parsed.get("a"));
        assertEquals("2", parsed.get("b"));
    }

    @Test
    void parseStringMapReturnsEmptyForNull() {
        assertTrue(codec.parseStringMap(null).isEmpty());
        assertTrue(codec.parseStringMap("").isEmpty());
        assertTrue(codec.parseStringMap("null").isEmpty());
    }

    @Test
    void fromJsonDeserializesObject() {
        Map<String, Object> p = codec.fromJson("{\"name\":\"test\",\"value\":42}", Map.class);
        assertEquals("test", p.get("name"));
        assertEquals(42.0, p.get("value"));
    }

    @Test
    void toJsonSerializesObject() {
        String json = codec.toJson(Map.of("name", "hello"));
        assertTrue(json.contains("\"name\":\"hello\""));
    }

    @Test
    void discoveredViaSpi() {
        JsonCodec defaultCodec = JsonCodec.getDefault();
        assertInstanceOf(GsonJsonCodec.class, defaultCodec);
    }
}

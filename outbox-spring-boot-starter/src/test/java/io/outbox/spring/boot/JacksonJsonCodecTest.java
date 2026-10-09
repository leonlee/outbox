package io.outbox.spring.boot;

import io.outbox.PayloadParseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonJsonCodecTest {

    private final JacksonJsonCodec codec = new JacksonJsonCodec(JsonMapper.builder().build());

    @Test
    void roundTripsHeaders() {
        Map<String, String> headers = Map.of("traceId", "abc", "tenant", "t1");
        assertEquals(headers, codec.parseStringMap(codec.toJson(headers)));
        assertTrue(codec.parseStringMap(" null ").isEmpty());
    }

    /** Jackson 3 exceptions are unchecked; they must still surface as the outbox's parse failure. */
    @Test
    void malformedJsonIsAPayloadParseException() {
        assertThrows(PayloadParseException.class, () -> codec.parseStringMap("{not json"));
        assertThrows(PayloadParseException.class, () -> codec.fromJson("[1,", Map.class));
    }
}

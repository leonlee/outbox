package outbox.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultJsonCodecTest {

  private final JsonCodec codec = JsonCodec.getDefault();

  @Test
  void toJsonWithEmptyMapReturnsNull() {
    Map<String, String> map = Map.of();

    String json = codec.toJson(map);

    assertNull(json); // Empty maps return null to save DB space
  }

  @Test
  void toJsonWithSingleEntry() {
    Map<String, String> map = Map.of("key", "value");

    String json = codec.toJson(map);

    assertEquals("{\"key\":\"value\"}", json);
  }

  @Test
  void toJsonWithMultipleEntries() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("a", "1");
    map.put("b", "2");

    String json = codec.toJson(map);

    assertEquals("{\"a\":\"1\",\"b\":\"2\"}", json);
  }

  @Test
  void toJsonEscapesSpecialCharacters() {
    Map<String, String> map = Map.of("msg", "Hello \"World\"\nNew\\Line");

    String json = codec.toJson(map);

    assertTrue(json.contains("\\\"World\\\""));
    assertTrue(json.contains("\\n"));
    assertTrue(json.contains("\\\\"));
  }

  @Test
  void toJsonWithNullMap() {
    String json = codec.toJson(null);

    assertNull(json);
  }

  @Test
  void toJsonWithNullValue() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("key", null);

    String json = codec.toJson(map);

    assertEquals("{\"key\":null}", json);
  }

  @Test
  void toJsonWithNullKeyThrows() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(null, "value");

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        codec.toJson(map));
    assertTrue(ex.getMessage().contains("headers cannot contain null keys"));
  }

  @Test
  void parseObjectSkipsNullValues() {
    // toJson encodes null values as JSON null
    Map<String, String> original = new LinkedHashMap<>();
    original.put("present", "value");
    original.put("absent", null);
    String json = codec.toJson(original);
    assertEquals("{\"present\":\"value\",\"absent\":null}", json);

    // parseObject skips null values (EventEnvelope rejects null header values)
    Map<String, String> parsed = codec.parseObject(json);
    assertEquals(1, parsed.size());
    assertEquals("value", parsed.get("present"));
    assertFalse(parsed.containsKey("absent"));
  }

  @Test
  void parseObjectWithEmptyJson() {
    Map<String, String> map = codec.parseObject("{}");

    assertTrue(map.isEmpty());
  }

  @Test
  void parseObjectWithSingleEntry() {
    Map<String, String> map = codec.parseObject("{\"key\":\"value\"}");

    assertEquals(1, map.size());
    assertEquals("value", map.get("key"));
  }

  @Test
  void parseObjectWithMultipleEntries() {
    Map<String, String> map = codec.parseObject("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}");

    assertEquals(3, map.size());
    assertEquals("1", map.get("a"));
    assertEquals("2", map.get("b"));
    assertEquals("3", map.get("c"));
  }

  @Test
  void parseObjectUnescapesSpecialCharacters() {
    Map<String, String> map = codec.parseObject("{\"msg\":\"Hello \\\"World\\\"\\nNew\\\\Line\"}");

    assertEquals("Hello \"World\"\nNew\\Line", map.get("msg"));
  }

  @Test
  void parseObjectWithNull() {
    Map<String, String> map = codec.parseObject(null);

    assertTrue(map.isEmpty());
  }

  @Test
  void parseObjectWithEmptyString() {
    Map<String, String> map = codec.parseObject("");

    assertTrue(map.isEmpty());
  }

  @Test
  void parseObjectWithWhitespace() {
    Map<String, String> map = codec.parseObject("  {  \"key\"  :  \"value\"  }  ");

    assertEquals("value", map.get("key"));
  }

  @Test
  void parseObjectRejectsNonObject() {
    assertThrows(IllegalArgumentException.class, () ->
        codec.parseObject("[\"array\"]"));

    assertThrows(IllegalArgumentException.class, () ->
        codec.parseObject("\"string\""));

    assertThrows(IllegalArgumentException.class, () ->
        codec.parseObject("123"));
  }

  @Test
  void roundTripPreservesData() {
    Map<String, String> original = new LinkedHashMap<>();
    original.put("simple", "value");
    original.put("quoted", "say \"hello\"");
    original.put("newline", "line1\nline2");
    original.put("backslash", "path\\to\\file");
    original.put("unicode", "café");

    String json = codec.toJson(original);
    Map<String, String> parsed = codec.parseObject(json);

    assertEquals(original, parsed);
  }

  @Test
  void handlesUnicodeEscapes() {
    Map<String, String> map = codec.parseObject("{\"emoji\":\"\\u0048\\u0065\\u006c\\u006c\\u006f\"}");

    assertEquals("Hello", map.get("emoji"));
  }

  @Test
  void handlesTabAndCarriageReturn() {
    Map<String, String> map = codec.parseObject("{\"text\":\"a\\tb\\rc\"}");

    assertEquals("a\tb\rc", map.get("text"));
  }

  @Test
  void getDefaultReturnsSingleton() {
    assertSame(JsonCodec.getDefault(), JsonCodec.getDefault());
  }

  @Test
  void invalidUnicodeEscapeThrows() {
    assertThrows(IllegalArgumentException.class, () ->
        codec.parseObject("{\"key\":\"\\uZZZZ\"}"));
  }

  @Test
  void unknownEscapeSequenceThrows() {
    assertThrows(IllegalArgumentException.class, () ->
        codec.parseObject("{\"key\":\"\\q\"}"));
  }

  @Test
  void unterminatedStringThrows() {
    assertThrows(IllegalArgumentException.class, () ->
        codec.parseObject("{\"key\":\"no closing quote}"));
  }
}

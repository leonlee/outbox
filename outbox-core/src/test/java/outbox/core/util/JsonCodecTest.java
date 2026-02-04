package outbox.core.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonCodecTest {

  @Test
  void toJsonWithEmptyMapReturnsNull() {
    Map<String, String> map = Map.of();

    String json = JsonCodec.toJson(map);

    assertNull(json); // Empty maps return null to save DB space
  }

  @Test
  void toJsonWithSingleEntry() {
    Map<String, String> map = Map.of("key", "value");

    String json = JsonCodec.toJson(map);

    assertEquals("{\"key\":\"value\"}", json);
  }

  @Test
  void toJsonWithMultipleEntries() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("a", "1");
    map.put("b", "2");

    String json = JsonCodec.toJson(map);

    assertEquals("{\"a\":\"1\",\"b\":\"2\"}", json);
  }

  @Test
  void toJsonEscapesSpecialCharacters() {
    Map<String, String> map = Map.of("msg", "Hello \"World\"\nNew\\Line");

    String json = JsonCodec.toJson(map);

    assertTrue(json.contains("\\\"World\\\""));
    assertTrue(json.contains("\\n"));
    assertTrue(json.contains("\\\\"));
  }

  @Test
  void toJsonWithNullMap() {
    String json = JsonCodec.toJson(null);

    assertNull(json);
  }

  @Test
  void toJsonWithNullValue() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("key", null);

    String json = JsonCodec.toJson(map);

    assertEquals("{\"key\":\"\"}", json);
  }

  @Test
  void parseObjectWithEmptyJson() {
    Map<String, String> map = JsonCodec.parseObject("{}");

    assertTrue(map.isEmpty());
  }

  @Test
  void parseObjectWithSingleEntry() {
    Map<String, String> map = JsonCodec.parseObject("{\"key\":\"value\"}");

    assertEquals(1, map.size());
    assertEquals("value", map.get("key"));
  }

  @Test
  void parseObjectWithMultipleEntries() {
    Map<String, String> map = JsonCodec.parseObject("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}");

    assertEquals(3, map.size());
    assertEquals("1", map.get("a"));
    assertEquals("2", map.get("b"));
    assertEquals("3", map.get("c"));
  }

  @Test
  void parseObjectUnescapesSpecialCharacters() {
    Map<String, String> map = JsonCodec.parseObject("{\"msg\":\"Hello \\\"World\\\"\\nNew\\\\Line\"}");

    assertEquals("Hello \"World\"\nNew\\Line", map.get("msg"));
  }

  @Test
  void parseObjectWithNull() {
    Map<String, String> map = JsonCodec.parseObject(null);

    assertTrue(map.isEmpty());
  }

  @Test
  void parseObjectWithEmptyString() {
    Map<String, String> map = JsonCodec.parseObject("");

    assertTrue(map.isEmpty());
  }

  @Test
  void parseObjectWithWhitespace() {
    Map<String, String> map = JsonCodec.parseObject("  {  \"key\"  :  \"value\"  }  ");

    assertEquals("value", map.get("key"));
  }

  @Test
  void parseObjectRejectsNonObject() {
    assertThrows(IllegalArgumentException.class, () ->
        JsonCodec.parseObject("[\"array\"]"));

    assertThrows(IllegalArgumentException.class, () ->
        JsonCodec.parseObject("\"string\""));

    assertThrows(IllegalArgumentException.class, () ->
        JsonCodec.parseObject("123"));
  }

  @Test
  void roundTripPreservesData() {
    Map<String, String> original = new LinkedHashMap<>();
    original.put("simple", "value");
    original.put("quoted", "say \"hello\"");
    original.put("newline", "line1\nline2");
    original.put("backslash", "path\\to\\file");
    original.put("unicode", "café");

    String json = JsonCodec.toJson(original);
    Map<String, String> parsed = JsonCodec.parseObject(json);

    assertEquals(original, parsed);
  }

  @Test
  void handlesUnicodeEscapes() {
    Map<String, String> map = JsonCodec.parseObject("{\"emoji\":\"\\u0048\\u0065\\u006c\\u006c\\u006f\"}");

    assertEquals("Hello", map.get("emoji"));
  }

  @Test
  void handlesTabAndCarriageReturn() {
    Map<String, String> map = JsonCodec.parseObject("{\"text\":\"a\\tb\\rc\"}");

    assertEquals("a\tb\rc", map.get("text"));
  }
}

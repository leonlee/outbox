package outbox.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight JSON encoder/decoder for {@code Map<String, String>} header maps.
 * Has no external dependencies; only supports flat string-to-string objects.
 *
 * <p>This is the default {@link JsonCodec} implementation, accessible via
 * {@link JsonCodec#getDefault()} or the singleton {@link #INSTANCE}.
 */
public final class DefaultJsonCodec implements JsonCodec {
  static final DefaultJsonCodec INSTANCE = new DefaultJsonCodec();

  DefaultJsonCodec() {
  }

  @Override
  public String toJson(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    boolean first = true;
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() == null) {
        throw new IllegalArgumentException("headers cannot contain null keys");
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(escape(entry.getKey())).append('"').append(':');
      if (entry.getValue() == null) {
        sb.append("null");
      } else {
        sb.append('"').append(escape(entry.getValue())).append('"');
      }
    }
    sb.append('}');
    return sb.toString();
  }

  @Override
  public Map<String, String> parseObject(String json) {
    if (json == null) {
      return Collections.emptyMap();
    }
    String trimmed = json.trim();
    if (trimmed.isEmpty() || "null".equals(trimmed)) {
      return Collections.emptyMap();
    }
    int len = trimmed.length();
    int idx = skipWhitespace(trimmed, 0);
    if (idx >= len || trimmed.charAt(idx) != '{') {
      throw new IllegalArgumentException("Expected JSON object");
    }
    idx++;
    Map<String, String> result = new LinkedHashMap<>();
    while (true) {
      idx = skipWhitespace(trimmed, idx);
      if (idx >= len) {
        throw new IllegalArgumentException("Unexpected end of JSON object");
      }
      char ch = trimmed.charAt(idx);
      if (ch == '}') {
        return result;
      }
      if (ch != '"') {
        throw new IllegalArgumentException("Expected string key");
      }
      ParseResult key = parseString(trimmed, idx + 1);
      idx = skipWhitespace(trimmed, key.nextIndex);
      if (idx >= len || trimmed.charAt(idx) != ':') {
        throw new IllegalArgumentException("Expected ':' after key");
      }
      idx = skipWhitespace(trimmed, idx + 1);
      if (idx + 3 < len && trimmed.startsWith("null", idx)) {
        // Skip null values — EventEnvelope rejects null header values
        idx += 4;
      } else if (idx >= len || trimmed.charAt(idx) != '"') {
        throw new IllegalArgumentException("Expected string value or null");
      } else {
        ParseResult value = parseString(trimmed, idx + 1);
        result.put(key.value, value.value);
        idx = value.nextIndex;
      }
      idx = skipWhitespace(trimmed, idx);
      if (idx >= len) {
        throw new IllegalArgumentException("Unexpected end of JSON object");
      }
      char next = trimmed.charAt(idx);
      if (next == ',') {
        idx++;
        continue;
      }
      if (next == '}') {
        return result;
      }
      throw new IllegalArgumentException("Expected ',' or '}'");
    }
  }

  private static int skipWhitespace(String input, int index) {
    int i = index;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        break;
      }
      i++;
    }
    return i;
  }

  private static ParseResult parseString(String input, int startIndex) {
    StringBuilder sb = new StringBuilder();
    int i = startIndex;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (c == '"') {
        return new ParseResult(sb.toString(), i + 1);
      }
      if (c == '\\') {
        if (i + 1 >= input.length()) {
          throw new IllegalArgumentException("Invalid escape sequence");
        }
        char next = input.charAt(i + 1);
        switch (next) {
          case '"':
          case '\\':
          case '/':
            sb.append(next);
            i += 2;
            break;
          case 'b':
            sb.append('\b');
            i += 2;
            break;
          case 'f':
            sb.append('\f');
            i += 2;
            break;
          case 'n':
            sb.append('\n');
            i += 2;
            break;
          case 'r':
            sb.append('\r');
            i += 2;
            break;
          case 't':
            sb.append('\t');
            i += 2;
            break;
          case 'u':
            if (i + 5 >= input.length()) {
              throw new IllegalArgumentException("Invalid unicode escape");
            }
            String hex = input.substring(i + 2, i + 6);
            try {
              int codePoint = Integer.parseInt(hex, 16);
              sb.append((char) codePoint);
            } catch (NumberFormatException ex) {
              throw new IllegalArgumentException("Invalid unicode escape", ex);
            }
            i += 6;
            break;
          default:
            throw new IllegalArgumentException("Unsupported escape sequence: \\" + next);
        }
      } else {
        sb.append(c);
        i++;
      }
    }
    throw new IllegalArgumentException("Unterminated string");
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  private static final class ParseResult {
    private final String value;
    private final int nextIndex;

    private ParseResult(String value, int nextIndex) {
      this.value = value;
      this.nextIndex = nextIndex;
    }
  }
}

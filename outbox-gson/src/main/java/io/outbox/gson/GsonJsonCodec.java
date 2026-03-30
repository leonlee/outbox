package io.outbox.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.outbox.PayloadParseException;
import io.outbox.spi.JsonCodec;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;

/**
 * {@link JsonCodec} implementation backed by Gson.
 *
 * <p>Registered as a {@link java.util.ServiceLoader} provider via
 * {@code META-INF/services/io.elestyle.outbox.spi.JsonCodec}.
 */
public final class GsonJsonCodec implements JsonCodec {

    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Gson gson;

    public GsonJsonCodec() {
        this(new GsonBuilder().disableHtmlEscaping().create());
    }

    public GsonJsonCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public String toJson(Object obj) {
        try {
            return gson.toJson(obj);
        } catch (JsonIOException e) {
            throw new PayloadParseException("Failed to serialize object to JSON", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return gson.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            throw new PayloadParseException("Failed to deserialize JSON to " + type.getSimpleName(), e);
        }
    }

    @Override
    public Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> result = gson.fromJson(json, MAP_TYPE);
            return result == null ? Collections.emptyMap() : result;
        } catch (JsonSyntaxException e) {
            throw new PayloadParseException("Failed to parse headers JSON", e);
        }
    }
}

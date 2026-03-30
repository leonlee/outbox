package io.outbox.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Holder for the default {@link JsonCodec} instance.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Programmatic override via {@link #set(JsonCodec)}</li>
 *   <li>{@link ServiceLoader} discovery (exactly one provider required)</li>
 * </ol>
 *
 * <p>If multiple SPI implementations are found, an {@link IllegalStateException} is thrown.
 * If none are found and no programmatic override was set, an {@link IllegalStateException} is thrown.
 */
final class JsonCodecHolder {
    private static volatile JsonCodec instance;

    private JsonCodecHolder() {
    }

    static JsonCodec get() {
        JsonCodec codec = instance;
        if (codec != null) {
            return codec;
        }
        synchronized (JsonCodecHolder.class) {
            if (instance != null) {
                return instance;
            }
            instance = discover();
            return instance;
        }
    }

    static synchronized void set(JsonCodec codec) {
        instance = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Resets the cached instance, allowing re-discovery or re-set.
     * Intended for testing only.
     */
    static synchronized void reset() {
        instance = null;
    }

    private static JsonCodec discover() {
        List<JsonCodec> found = new ArrayList<>();
        for (JsonCodec codec : ServiceLoader.load(JsonCodec.class)) {
            found.add(codec);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "No JsonCodec implementation found. "
                            + "Add outbox-gson to the classpath, or call JsonCodec.setDefault() "
                            + "with a custom implementation (e.g. JacksonJsonCodec in Spring Boot).");
        }
        if (found.size() > 1) {
            List<String> names = found.stream()
                    .map(c -> c.getClass().getName())
                    .toList();
            throw new IllegalStateException(
                    "Multiple JsonCodec SPI implementations found: " + names
                            + ". Remove extra implementations or configure an explicit JsonCodec bean.");
        }
        return found.get(0);
    }
}

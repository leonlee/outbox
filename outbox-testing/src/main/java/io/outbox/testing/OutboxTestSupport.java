package io.outbox.testing;

import io.outbox.AggregateType;
import io.outbox.DefaultOutboxWriter;
import io.outbox.EventListener;
import io.outbox.EventType;
import io.outbox.OutboxWriter;
import io.outbox.WriterHook;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.registry.ListenerRegistry;

import java.util.Objects;

/**
 * Convenience builder for setting up outbox test fixtures without a database.
 *
 * <p>Wires together a {@link StubTxContext}, {@link InMemoryOutboxStore},
 * {@link DefaultOutboxWriter}, and {@link DefaultListenerRegistry} for
 * fast in-memory unit testing.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var test = OutboxTestSupport.create()
 *     .register("OrderPlaced", event -> {
 *         // verify event handling
 *     })
 *     .build();
 *
 * test.writer().write(EventEnvelope.ofJson("OrderPlaced", "{}"));
 * test.txContext().runAfterCommit(); // triggers dispatch
 * assertEquals(1, test.store().size());
 * }</pre>
 */
public final class OutboxTestSupport {
    private final StubTxContext txContext;
    private final InMemoryOutboxStore store;
    private final OutboxWriter writer;
    private final ListenerRegistry listenerRegistry;

    private OutboxTestSupport(StubTxContext txContext, InMemoryOutboxStore store,
                              OutboxWriter writer, ListenerRegistry listenerRegistry) {
        this.txContext = txContext;
        this.store = store;
        this.writer = writer;
        this.listenerRegistry = listenerRegistry;
    }

    /**
     * Returns the stub transaction context for simulating commits/rollbacks.
     *
     * @return the stub tx context
     */
    public StubTxContext txContext() {
        return txContext;
    }

    /**
     * Returns the in-memory store for inspecting persisted events.
     *
     * @return the in-memory outbox store
     */
    public InMemoryOutboxStore store() {
        return store;
    }

    /**
     * Returns the outbox writer for writing events.
     *
     * @return the outbox writer
     */
    public OutboxWriter writer() {
        return writer;
    }

    /**
     * Returns the listener registry.
     *
     * @return the listener registry
     */
    public ListenerRegistry listenerRegistry() {
        return listenerRegistry;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public static Builder create() {
        return new Builder();
    }

    /**
     * Builder for {@link OutboxTestSupport}.
     */
    public static final class Builder {
        private final DefaultListenerRegistry registry = new DefaultListenerRegistry();
        private WriterHook writerHook;

        Builder() {
        }

        /**
         * Registers a listener for an event type (global aggregate).
         *
         * @param eventType the event type name
         * @param listener  the event listener
         * @return this builder
         */
        public Builder register(String eventType, EventListener listener) {
            registry.register(eventType, listener);
            return this;
        }

        /**
         * Registers a listener for an event type (global aggregate).
         *
         * @param eventType the event type
         * @param listener  the event listener
         * @return this builder
         */
        public Builder register(EventType eventType, EventListener listener) {
            registry.register(eventType, listener);
            return this;
        }

        /**
         * Registers a listener for an aggregate-scoped event type.
         *
         * @param aggregateType the aggregate type name
         * @param eventType     the event type name
         * @param listener      the event listener
         * @return this builder
         */
        public Builder register(String aggregateType, String eventType, EventListener listener) {
            registry.register(aggregateType, eventType, listener);
            return this;
        }

        /**
         * Registers a listener for an aggregate-scoped event type.
         *
         * @param aggregateType the aggregate type
         * @param eventType     the event type
         * @param listener      the event listener
         * @return this builder
         */
        public Builder register(AggregateType aggregateType, EventType eventType, EventListener listener) {
            registry.register(aggregateType, eventType, listener);
            return this;
        }

        /**
         * Sets a custom writer hook.
         *
         * @param writerHook the writer hook
         * @return this builder
         */
        public Builder writerHook(WriterHook writerHook) {
            this.writerHook = Objects.requireNonNull(writerHook, "writerHook");
            return this;
        }

        /**
         * Builds the test support with all fixtures wired together.
         *
         * @return a new {@link OutboxTestSupport}
         */
        public OutboxTestSupport build() {
            StubTxContext txContext = new StubTxContext();
            InMemoryOutboxStore store = new InMemoryOutboxStore();
            OutboxWriter writer = writerHook != null
                    ? new DefaultOutboxWriter(txContext, store, writerHook)
                    : new DefaultOutboxWriter(txContext, store);
            return new OutboxTestSupport(txContext, store, writer, registry);
        }
    }
}

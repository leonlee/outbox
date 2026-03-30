package io.outbox.registry;

import io.outbox.AggregateType;
import io.outbox.BoundEventListener;
import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.EventListener;
import io.outbox.StringAggregateType;
import io.outbox.StringEventType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultListenerRegistryTest {

    @Test
    void returnsNullForUnregistered() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();

        assertNull(registry.listenerFor(AggregateType.GLOBAL.name(), "Unknown"));
    }

    @Test
    void returnsRegisteredListener() throws Exception {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        AtomicInteger called = new AtomicInteger();

        registry.register("UserCreated", event -> {
            called.incrementAndGet();
            return DispatchResult.done();
        });

        EventListener listener = registry.listenerFor(AggregateType.GLOBAL.name(), "UserCreated");
        assertNotNull(listener);

        listener.onEvent(null);
        assertEquals(1, called.get());
    }

    @Test
    void duplicateRegistrationThrows() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        registry.register("UserCreated", event -> DispatchResult.done());

        assertThrows(IllegalStateException.class, () ->
                registry.register("UserCreated", event -> DispatchResult.done()));
    }

    @Test
    void aggregateTypeScopedRegistration() throws Exception {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        AtomicInteger orderCalled = new AtomicInteger();
        AtomicInteger userCalled = new AtomicInteger();

        registry.register("Order", "Created", event -> {
            orderCalled.incrementAndGet();
            return DispatchResult.done();
        });
        registry.register("User", "Created", event -> {
            userCalled.incrementAndGet();
            return DispatchResult.done();
        });

        EventListener orderListener = registry.listenerFor("Order", "Created");
        EventListener userListener = registry.listenerFor("User", "Created");

        assertNotNull(orderListener);
        assertNotNull(userListener);

        orderListener.onEvent(null);
        userListener.onEvent(null);

        assertEquals(1, orderCalled.get());
        assertEquals(1, userCalled.get());
    }

    @Test
    void convenienceRegisterUsesGlobal() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        registry.register("UserCreated", event -> DispatchResult.done());

        assertNotNull(registry.listenerFor(AggregateType.GLOBAL.name(), "UserCreated"));
    }

    @Test
    void registerWithTypeSafeTypes() throws Exception {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        AtomicInteger called = new AtomicInteger();

        AggregateType orderType = StringAggregateType.of("Order");
        registry.register(orderType, StringEventType.of("OrderPlaced"), event -> {
            called.incrementAndGet();
            return DispatchResult.done();
        });

        EventListener listener = registry.listenerFor("Order", "OrderPlaced");
        assertNotNull(listener);

        listener.onEvent(null);
        assertEquals(1, called.get());
    }

    @Test
    void registerWithAggregateTypeAndStringEventType() throws Exception {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        AtomicInteger called = new AtomicInteger();

        AggregateType userType = StringAggregateType.of("User");
        registry.register(userType, "UserCreated", event -> {
            called.incrementAndGet();
            return DispatchResult.done();
        });

        EventListener listener = registry.listenerFor("User", "UserCreated");
        assertNotNull(listener);

        listener.onEvent(null);
        assertEquals(1, called.get());
    }

    @Test
    void registerWithEventTypeInterface() throws Exception {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        AtomicInteger called = new AtomicInteger();

        registry.register(StringEventType.of("OrderPlaced"), event -> {
            called.incrementAndGet();
            return DispatchResult.done();
        });

        EventListener listener = registry.listenerFor(AggregateType.GLOBAL.name(), "OrderPlaced");
        assertNotNull(listener);

        listener.onEvent(null);
        assertEquals(1, called.get());
    }

    @Test
    void fluentApiSupportsChaining() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry()
                .register("A", event -> DispatchResult.done())
                .register("B", event -> DispatchResult.done());

        assertNotNull(registry.listenerFor(AggregateType.GLOBAL.name(), "A"));
        assertNotNull(registry.listenerFor(AggregateType.GLOBAL.name(), "B"));
        assertNull(registry.listenerFor(AggregateType.GLOBAL.name(), "C"));
    }

    @Test
    void colonInTypeNamesDoesNotCollide() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        AtomicInteger firstCalled = new AtomicInteger();
        AtomicInteger secondCalled = new AtomicInteger();

        // "a:b" + "c" vs "a" + "b:c" — previously collided as "a:b:c"
        registry.register("a:b", "c", event -> {
            firstCalled.incrementAndGet();
            return DispatchResult.done();
        });
        registry.register("a", "b:c", event -> {
            secondCalled.incrementAndGet();
            return DispatchResult.done();
        });

        assertNotNull(registry.listenerFor("a:b", "c"));
        assertNotNull(registry.listenerFor("a", "b:c"));
        assertNotSame(registry.listenerFor("a:b", "c"), registry.listenerFor("a", "b:c"));
    }

    @Test
    void registerRejectsNullAggregateType() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();

        assertThrows(NullPointerException.class, () ->
                registry.register((String) null, "E", event -> DispatchResult.done()));
    }

    @Test
    void registerRejectsNullEventType() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();

        assertThrows(NullPointerException.class, () ->
                registry.register("A", (String) null, event -> DispatchResult.done()));
    }

    @Test
    void registerRejectsNullListener() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();

        assertThrows(NullPointerException.class, () ->
                registry.register("A", "E", null));
    }

    @Test
    void registerBoundEventListener() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        BoundEventListener listener = new BoundEventListener("Order", "OrderPlaced") {
            @Override
            public DispatchResult onEvent(EventEnvelope envelope) {
                return DispatchResult.done();
            }
        };

        registry.register(listener);

        assertSame(listener, registry.listenerFor("Order", "OrderPlaced"));
    }

    @Test
    void registerBoundEventListenerDuplicateThrows() {
        DefaultListenerRegistry registry = new DefaultListenerRegistry();
        registry.register(new BoundEventListener("Order", "OrderPlaced") {
            @Override
            public DispatchResult onEvent(EventEnvelope envelope) {
                return DispatchResult.done();
            }
        });

        assertThrows(IllegalStateException.class, () ->
                registry.register(new BoundEventListener("Order", "OrderPlaced") {
                    @Override
                    public DispatchResult onEvent(EventEnvelope envelope) {
                        return DispatchResult.done();
                    }
                }));
    }

    @Test
    void boundEventListenerRejectsNullAggregateType() {
        assertThrows(NullPointerException.class, () ->
                new BoundEventListener(null, "E") {
                    @Override
                    public DispatchResult onEvent(EventEnvelope envelope) {
                        return DispatchResult.done();
                    }
                });
    }

    @Test
    void boundEventListenerRejectsNullEventType() {
        assertThrows(NullPointerException.class, () ->
                new BoundEventListener("A", (String) null) {
                    @Override
                    public DispatchResult onEvent(EventEnvelope envelope) {
                        return DispatchResult.done();
                    }
                });
    }
}

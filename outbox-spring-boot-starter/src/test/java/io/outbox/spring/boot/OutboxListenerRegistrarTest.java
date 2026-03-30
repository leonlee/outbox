package io.outbox.spring.boot;

import io.outbox.AggregateType;
import io.outbox.BoundEventListener;
import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.EventListener;
import io.outbox.EventType;
import io.outbox.registry.DefaultListenerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OutboxListenerRegistrarTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void registersStringBasedListener() {
        runner.withUserConfiguration(StringListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("__GLOBAL__", "TestEvent"));
        });
    }

    @Test
    void registersStringBasedListenerWithAggregate() {
        runner.withUserConfiguration(AggregateStringListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("Order", "OrderPlaced"));
        });
    }

    @Test
    void registersClassBasedEventType() {
        runner.withUserConfiguration(ClassBasedListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("__GLOBAL__", "TestClassEvent"));
        });
    }

    @Test
    void registersEnumBasedTypes() {
        runner.withUserConfiguration(EnumBasedListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("TestAggregate", "TestEnumEvent"));
        });
    }

    @Test
    void classBasedTakesPrecedenceOverString() {
        runner.withUserConfiguration(PrecedenceListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            // Class-based "TestClassEvent" should win over string "IgnoredEvent"
            assertNotNull(registry.listenerFor("__GLOBAL__", "TestClassEvent"));
            assertNull(registry.listenerFor("__GLOBAL__", "IgnoredEvent"));
        });
    }

    @Test
    void failsWhenBeanDoesNotImplementEventListener() {
        runner.withUserConfiguration(NotAListenerConfig.class).run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertInstanceOf(BeanCreationException.class, ctx.getStartupFailure());
        });
    }

    @Test
    void failsWhenNoEventTypeSpecified() {
        runner.withUserConfiguration(NoEventTypeConfig.class).run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertInstanceOf(BeanCreationException.class, ctx.getStartupFailure());
        });
    }

    // ── processBoundListeners tests ─────────────────────────────

    @Test
    void registersBoundListenerWithStringTypes() {
        runner.withUserConfiguration(BoundListenerStringConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            var listener = registry.listenerFor("Order", "OrderCreated");
            assertNotNull(listener, "BoundEventListener should be registered");
            assertInstanceOf(StringBoundListener.class, listener);
        });
    }

    @Test
    void registersBoundListenerWithEnumTypes() {
        runner.withUserConfiguration(BoundListenerEnumConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            var listener = registry.listenerFor("TestAggregate", "TestEnumEvent");
            assertNotNull(listener, "BoundEventListener with enum types should be registered");
            assertInstanceOf(EnumBoundListener.class, listener);
        });
    }

    @Test
    void registersMultipleBoundListeners() {
        runner.withUserConfiguration(MultipleBoundListenersConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("Order", "OrderCreated"));
            assertNotNull(registry.listenerFor("TestAggregate", "TestEnumEvent"));
        });
    }

    @Test
    void registersBoundAndAnnotatedListenersTogether() {
        runner.withUserConfiguration(MixedListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("Order", "OrderCreated"),
                    "BoundEventListener should be registered");
            assertNotNull(registry.listenerFor("__GLOBAL__", "TestEvent"),
                    "Annotated listener should be registered");
        });
    }

    @Test
    void failsWhenBoundAndAnnotatedListenerHaveSameKey() {
        runner.withUserConfiguration(DuplicateKeyConfig.class).run(ctx -> {
            assertNotNull(ctx.getStartupFailure(), "Context should fail to start due to duplicate key");
            assertInstanceOf(IllegalStateException.class, ctx.getStartupFailure());
        });
    }

    // ── Test support ─────────────────────────────────────────────

    public record TestClassEventType() implements EventType {
        @Override
        public String name() {
            return "TestClassEvent";
        }
    }

    public enum TestEnumEventType implements EventType {
        TestEnumEvent
    }

    public enum TestEnumAggregateType implements AggregateType {
        TestAggregate
    }

    @OutboxListener(eventType = "TestEvent")
    static class StringBasedListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @OutboxListener(eventType = "OrderPlaced", aggregateType = "Order")
    static class AggregateStringListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @OutboxListener(eventTypeClass = TestClassEventType.class)
    static class ClassBasedListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @OutboxListener(eventTypeClass = TestEnumEventType.class, aggregateTypeClass = TestEnumAggregateType.class)
    static class EnumBasedListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @OutboxListener(eventType = "IgnoredEvent", eventTypeClass = TestClassEventType.class)
    static class PrecedenceListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @OutboxListener(eventType = "SomeEvent")
    static class NotAListenerBean {
        // Does NOT implement EventListener
    }

    @OutboxListener
    static class NoEventTypeListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @Configuration
    static class BaseConfig {
        @Bean
        DefaultListenerRegistry listenerRegistry() {
            return new DefaultListenerRegistry();
        }
    }

    @Configuration
    static class StringListenerConfig extends BaseConfig {
        @Bean
        StringBasedListener stringBasedListener() {
            return new StringBasedListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class AggregateStringListenerConfig extends BaseConfig {
        @Bean
        AggregateStringListener aggregateStringListener() {
            return new AggregateStringListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class ClassBasedListenerConfig extends BaseConfig {
        @Bean
        ClassBasedListener classBasedListener() {
            return new ClassBasedListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class EnumBasedListenerConfig extends BaseConfig {
        @Bean
        EnumBasedListener enumBasedListener() {
            return new EnumBasedListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class PrecedenceListenerConfig extends BaseConfig {
        @Bean
        PrecedenceListener precedenceListener() {
            return new PrecedenceListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class NotAListenerConfig extends BaseConfig {
        @Bean
        NotAListenerBean notAListenerBean() {
            return new NotAListenerBean();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    static class StringBoundListener extends BoundEventListener {
        public StringBoundListener() {
            super("Order", "OrderCreated");
        }

        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    static class EnumBoundListener extends BoundEventListener {
        public EnumBoundListener() {
            super(TestEnumAggregateType.TestAggregate, TestEnumEventType.TestEnumEvent);
        }

        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @Configuration
    static class BoundListenerStringConfig extends BaseConfig {
        @Bean
        StringBoundListener stringBoundListener() {
            return new StringBoundListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class BoundListenerEnumConfig extends BaseConfig {
        @Bean
        EnumBoundListener enumBoundListener() {
            return new EnumBoundListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class MultipleBoundListenersConfig extends BaseConfig {
        @Bean
        StringBoundListener stringBoundListener() {
            return new StringBoundListener();
        }

        @Bean
        EnumBoundListener enumBoundListener() {
            return new EnumBoundListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class MixedListenerConfig extends BaseConfig {
        @Bean
        StringBoundListener stringBoundListener() {
            return new StringBoundListener();
        }

        @Bean
        StringBasedListener stringBasedListener() {
            return new StringBasedListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    static class ConflictingBoundListener extends BoundEventListener {
        public ConflictingBoundListener() {
            super("__GLOBAL__", "TestEvent"); // same key as StringBasedListener
        }

        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @Configuration
    static class DuplicateKeyConfig extends BaseConfig {
        @Bean
        StringBasedListener stringBasedListener() {
            return new StringBasedListener();
        }

        @Bean
        ConflictingBoundListener conflictingBoundListener() {
            return new ConflictingBoundListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }

    @Configuration
    static class NoEventTypeConfig extends BaseConfig {
        @Bean
        NoEventTypeListener noEventTypeListener() {
            return new NoEventTypeListener();
        }

        @Bean
        OutboxListenerRegistrar registrar(
                org.springframework.beans.factory.ListableBeanFactory bf,
                DefaultListenerRegistry registry) {
            return new OutboxListenerRegistrar(bf, registry);
        }
    }
}

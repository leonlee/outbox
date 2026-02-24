package outbox.spring.boot;

import outbox.AggregateType;
import outbox.EventType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean as an outbox event listener.
 *
 * <p>The annotated bean must implement {@link outbox.EventListener}. The annotation
 * specifies which (aggregateType, eventType) pair this listener handles.
 *
 * <h2>String-based registration</h2>
 * <pre>{@code
 * @Component
 * @OutboxListener(eventType = "OrderPlaced", aggregateType = "Order")
 * public class OrderListener implements EventListener {
 *   public void onEvent(EventEnvelope event) { ... }
 * }
 * }</pre>
 *
 * <h2>Type-safe class-based registration</h2>
 * <pre>{@code
 * @Component
 * @OutboxListener(eventTypeClass = OrderPlaced.class)
 * public class OrderListener implements EventListener { ... }
 * }</pre>
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>{@code eventTypeClass} takes precedence over {@code eventType}</li>
 *   <li>{@code aggregateTypeClass} takes precedence over {@code aggregateType}</li>
 *   <li>Exactly one of {@code eventType}/{@code eventTypeClass} must be specified</li>
 *   <li>Aggregate type defaults to {@code "__GLOBAL__"} if neither is set</li>
 * </ul>
 *
 * @see outbox.EventListener
 * @see OutboxListenerRegistrar
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OutboxListener {

    /**
     * Event type name (string-based).
     */
    String eventType() default "";

    /**
     * Aggregate type name (string-based). Defaults to GLOBAL.
     */
    String aggregateType() default "__GLOBAL__";

    /**
     * Event type class (type-safe). Takes precedence over {@link #eventType()}.
     * Must have a no-arg constructor (or be an enum).
     */
    Class<? extends EventType> eventTypeClass() default EventType.class;

    /**
     * Aggregate type class (type-safe). Takes precedence over {@link #aggregateType()}.
     * Must have a no-arg constructor (or be an enum).
     */
    Class<? extends AggregateType> aggregateTypeClass() default AggregateType.class;
}

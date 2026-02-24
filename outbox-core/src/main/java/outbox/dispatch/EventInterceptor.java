package outbox.dispatch;

import outbox.EventEnvelope;

/**
 * Cross-cutting hook for observing or modifying event dispatch.
 *
 * <p>Interceptors run around listener invocation:
 * <ol>
 *   <li>{@link #beforeDispatch} in registration order</li>
 *   <li>Listener execution</li>
 *   <li>{@link #afterDispatch} in reverse registration order</li>
 * </ol>
 *
 * <p>If {@code beforeDispatch} throws, dispatch short-circuits to
 * retry/dead handling. {@code afterDispatch} exceptions are logged
 * but swallowed.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * OutboxDispatcher.builder()
 *     .interceptor(EventInterceptor.before(event ->
 *         audit.log(event.eventType(), event.eventId())))
 *     .interceptor(EventInterceptor.after((event, error) -> {
 *         if (error != null) metrics.increment("dispatch.failure");
 *     }))
 *     .build();
 * }</pre>
 */
public interface EventInterceptor {

    /**
     * Called before the event listener is invoked.
     *
     * @param event the event about to be dispatched
     * @throws Exception to abort dispatch and trigger retry/dead handling
     */
    default void beforeDispatch(EventEnvelope event) throws Exception {
    }

    /**
     * Called after listener invocation (or after beforeDispatch failure).
     *
     * @param event the event that was dispatched
     * @param error null on success, the exception on failure
     */
    default void afterDispatch(EventEnvelope event, Exception error) {
    }

    /**
     * Creates an interceptor with only a beforeDispatch hook.
     */
    static EventInterceptor before(BeforeHook hook) {
        return new EventInterceptor() {
            @Override
            public void beforeDispatch(EventEnvelope event) throws Exception {
                hook.accept(event);
            }
        };
    }

    /**
     * Creates an interceptor with only an afterDispatch hook.
     */
    static EventInterceptor after(AfterHook hook) {
        return new EventInterceptor() {
            @Override
            public void afterDispatch(EventEnvelope event, Exception error) {
                hook.accept(event, error);
            }
        };
    }

    @FunctionalInterface
    interface BeforeHook {
        void accept(EventEnvelope event) throws Exception;
    }

    @FunctionalInterface
    interface AfterHook {
        void accept(EventEnvelope event, Exception error);
    }
}

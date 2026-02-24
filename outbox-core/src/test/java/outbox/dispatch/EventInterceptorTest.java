package outbox.dispatch;

import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class EventInterceptorTest {

    @Test
    void defaultMethodsAreNoOps() throws Exception {
        EventInterceptor interceptor = new EventInterceptor() {
        };
        EventEnvelope event = EventEnvelope.ofJson("Test", "{}");

        assertDoesNotThrow(() -> interceptor.beforeDispatch(event));
        assertDoesNotThrow(() -> interceptor.afterDispatch(event, null));
        assertDoesNotThrow(() -> interceptor.afterDispatch(event, new RuntimeException("err")));
    }

    @Test
    void beforeFactoryCreatesWorkingInterceptor() throws Exception {
        AtomicInteger called = new AtomicInteger();
        EventInterceptor interceptor = EventInterceptor.before(event -> called.incrementAndGet());

        EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
        interceptor.beforeDispatch(event);

        assertEquals(1, called.get());
    }

    @Test
    void afterFactoryCreatesWorkingInterceptor() {
        AtomicReference<Exception> capturedError = new AtomicReference<>();
        EventInterceptor interceptor = EventInterceptor.after((event, error) -> capturedError.set(error));

        EventEnvelope event = EventEnvelope.ofJson("Test", "{}");

        interceptor.afterDispatch(event, null);
        assertNull(capturedError.get());

        RuntimeException ex = new RuntimeException("boom");
        interceptor.afterDispatch(event, ex);
        assertSame(ex, capturedError.get());
    }
}

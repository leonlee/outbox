package io.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryAfterExceptionTest {

    @Test
    void constructorWithDuration() {
        Duration delay = Duration.ofSeconds(60);
        var ex = new RetryAfterException(delay);
        assertEquals(delay, ex.retryAfter());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void constructorWithDurationAndMessage() {
        Duration delay = Duration.ofMinutes(5);
        var ex = new RetryAfterException(delay, "rate limited");
        assertEquals(delay, ex.retryAfter());
        assertEquals("rate limited", ex.getMessage());
    }

    @Test
    void constructorWithDurationAndCause() {
        Duration delay = Duration.ofSeconds(30);
        var cause = new RuntimeException("upstream");
        var ex = new RetryAfterException(delay, cause);
        assertEquals(delay, ex.retryAfter());
        assertSame(cause, ex.getCause());
    }

    @Test
    void constructorWithDurationMessageAndCause() {
        Duration delay = Duration.ofSeconds(10);
        var cause = new RuntimeException("fail");
        var ex = new RetryAfterException(delay, "custom msg", cause);
        assertEquals(delay, ex.retryAfter());
        assertEquals("custom msg", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void zeroDurationIsAllowed() {
        var ex = new RetryAfterException(Duration.ZERO);
        assertEquals(Duration.ZERO, ex.retryAfter());
    }

    @Test
    void rejectsNullDuration() {
        assertThrows(NullPointerException.class, () -> new RetryAfterException(null));
    }

    @Test
    void rejectsNullDurationWithMessage() {
        assertThrows(NullPointerException.class, () -> new RetryAfterException(null, "msg"));
    }

    @Test
    void rejectsNullDurationWithCause() {
        assertThrows(NullPointerException.class, () -> new RetryAfterException(null, new RuntimeException()));
    }

    @Test
    void rejectsNullDurationWithMessageAndCause() {
        assertThrows(NullPointerException.class, () -> new RetryAfterException(null, "msg", new RuntimeException()));
    }

    @Test
    void rejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> new RetryAfterException(Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsNegativeDurationWithMessage() {
        assertThrows(IllegalArgumentException.class, () -> new RetryAfterException(Duration.ofSeconds(-1), "msg"));
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new RetryAfterException(Duration.ofSeconds(1)));
    }
}

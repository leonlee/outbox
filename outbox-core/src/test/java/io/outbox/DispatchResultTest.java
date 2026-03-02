package io.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatchResultTest {

    @Test
    void doneSingletonReturnsSameInstance() {
        assertSame(DispatchResult.DONE, DispatchResult.done());
    }

    @Test
    void doneIsDoneInstance() {
        assertInstanceOf(DispatchResult.Done.class, DispatchResult.done());
    }

    @Test
    void retryAfterStoresDelay() {
        Duration delay = Duration.ofSeconds(30);
        DispatchResult.RetryAfter result = DispatchResult.retryAfter(delay);
        assertEquals(delay, result.delay());
    }

    @Test
    void retryAfterIsRetryAfterInstance() {
        assertInstanceOf(DispatchResult.RetryAfter.class, DispatchResult.retryAfter(Duration.ofSeconds(1)));
    }

    @Test
    void retryAfterWithZeroDurationIsAllowed() {
        DispatchResult.RetryAfter result = DispatchResult.retryAfter(Duration.ZERO);
        assertEquals(Duration.ZERO, result.delay());
    }

    @Test
    void retryAfterRejectsNull() {
        assertThrows(NullPointerException.class, () -> DispatchResult.retryAfter(null));
    }

    @Test
    void retryAfterRejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> DispatchResult.retryAfter(Duration.ofSeconds(-1)));
    }
}

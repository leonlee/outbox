package io.outbox.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultInFlightTrackerTest {

    @Test
    void acquireSucceedsForNewEventId() {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker();

        assertTrue(tracker.tryAcquire("event-1"));
    }

    @Test
    void acquireFailsForAlreadyAcquiredEventId() {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker();

        assertTrue(tracker.tryAcquire("event-1"));
        assertFalse(tracker.tryAcquire("event-1"));
    }

    @Test
    void releaseAllowsReacquisition() {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker();

        assertTrue(tracker.tryAcquire("event-1"));
        tracker.release("event-1");
        assertTrue(tracker.tryAcquire("event-1"));
    }

    @Test
    void multipleEventsCanBeAcquiredIndependently() {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker();

        assertTrue(tracker.tryAcquire("event-1"));
        assertTrue(tracker.tryAcquire("event-2"));
        assertTrue(tracker.tryAcquire("event-3"));

        assertFalse(tracker.tryAcquire("event-1"));
        assertFalse(tracker.tryAcquire("event-2"));

        tracker.release("event-2");
        assertTrue(tracker.tryAcquire("event-2"));
    }

    @Test
    void releaseNonExistentEventIdIsNoOp() {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker();

        // Should not throw
        tracker.release("non-existent");
        assertTrue(tracker.tryAcquire("non-existent"));
    }

    @Test
    void ttlAllowsReacquisitionAfterExpiry() throws InterruptedException {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker(50); // 50ms TTL

        assertTrue(tracker.tryAcquire("event-1"));
        assertFalse(tracker.tryAcquire("event-1"));

        Thread.sleep(100); // Wait for TTL to expire

        assertTrue(tracker.tryAcquire("event-1"));
    }

    @Test
    void zeroTtlMeansNoExpiry() throws InterruptedException {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker(0);

        assertTrue(tracker.tryAcquire("event-1"));

        Thread.sleep(10);

        assertFalse(tracker.tryAcquire("event-1"));
    }

    @Test
    void defaultConstructorHasZeroTtl() throws InterruptedException {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker();

        assertTrue(tracker.tryAcquire("event-1"));

        Thread.sleep(10);

        assertFalse(tracker.tryAcquire("event-1"));
    }

    @Test
    void negativeTtlBehavesLikeZero() throws InterruptedException {
        DefaultInFlightTracker tracker = new DefaultInFlightTracker(-100);

        assertTrue(tracker.tryAcquire("event-1"));

        Thread.sleep(10);

        // Negative TTL should not enable expiry — entry persists until released
        assertFalse(tracker.tryAcquire("event-1"));
    }
}

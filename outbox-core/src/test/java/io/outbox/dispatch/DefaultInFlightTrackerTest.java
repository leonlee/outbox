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

    /**
     * Past its TTL, an unsettled entry must stop reporting as running.
     *
     * <p>{@link DefaultInFlightTracker#tryAcquire} deliberately reclaims such an entry — it is how
     * an event whose worker never came back gets delivered again. A caller that consults
     * {@link DefaultInFlightTracker#isRunning} to decide whether to queue a copy would otherwise
     * drop every redelivery before it reached tryAcquire, and the event would never move.
     */
    @Test
    void anEntryPastItsTtlIsNoLongerRunning() throws Exception {
        var tracker = new DefaultInFlightTracker(100);
        assertTrue(tracker.tryAcquire("e1"));
        assertTrue(tracker.isRunning("e1"), "held and unsettled");

        Thread.sleep(250);

        assertFalse(tracker.isRunning("e1"), "expired entries are not running");
        assertTrue(tracker.tryAcquire("e1"), "and tryAcquire agrees, which is the point");
    }

    /** With no TTL there is nothing to expire, so a held entry stays running indefinitely. */
    @Test
    void withoutATtlAHeldEntryStaysRunning() throws Exception {
        var tracker = new DefaultInFlightTracker();
        assertTrue(tracker.tryAcquire("e1"));
        Thread.sleep(50);
        assertTrue(tracker.isRunning("e1"));
        assertFalse(tracker.tryAcquire("e1"), "and it cannot be reacquired either");
    }

    /**
     * A completion may only end the acquisition it belongs to.
     *
     * <p>Once entries expire, a listener that outruns the TTL has its entry reclaimed and handed to
     * a second worker. If the first worker's late {@code release} were keyed on the id alone it
     * would drop the second worker's entry, and a third copy could start while that one is still
     * running — the tracker handing out the duplicate it exists to prevent.
     */
    @Test
    void aLateReleaseCannotEndSomebodyElsesAcquisition() throws Exception {
        var tracker = new DefaultInFlightTracker(100);
        long first = tracker.acquire("e1");
        assertTrue(first != InFlightTracker.NOT_ACQUIRED);

        Thread.sleep(250);                                  // the first worker overruns the TTL
        long second = tracker.acquire("e1");                // reclaimed by another worker
        assertTrue(second != InFlightTracker.NOT_ACQUIRED);
        assertTrue(second != first, "a reclaim is a new acquisition");

        tracker.release("e1", first);                       // the original worker finally returns
        assertTrue(tracker.isRunning("e1"), "the second worker still holds it");
        assertFalse(tracker.tryAcquire("e1"), "so nobody else may start");

        tracker.release("e1", second);
        assertTrue(tracker.tryAcquire("e1"), "released once its own acquisition ends");
    }

    /** Same rule for the settled marker: a stale completion must not stamp it. */
    @Test
    void aLateSettleCannotMarkSomebodyElsesAcquisition() throws Exception {
        var tracker = new DefaultInFlightTracker(100);
        long first = tracker.acquire("e1");
        Thread.sleep(250);
        long second = tracker.acquire("e1");

        tracker.markSettled("e1", first);
        assertFalse(tracker.isSettled("e1"), "the live acquisition is still running, not settled");

        tracker.markSettled("e1", second);
        assertTrue(tracker.isSettled("e1"));
    }

    /**
     * Tokens are strictly positive, whatever state the counter is in.
     *
     * <p>The negative range contains the {@code NOT_ACQUIRED} sentinel — an acquisition minted
     * {@code -1} would read as a refusal to every caller, and its entry would sit in the tracker
     * with no owner able to complete it. The counter is driven to the boundaries by reflection
     * because reaching them honestly takes 2^63 increments.
     */
    @Test
    void tokensStayPositiveAcrossTheCounterWrap() throws Exception {
        var tracker = new DefaultInFlightTracker(60_000);
        var field = DefaultInFlightTracker.class.getDeclaredField("tokens");
        field.setAccessible(true);
        var tokens = (java.util.concurrent.atomic.AtomicLong) field.get(tracker);

        int i = 0;
        for (long boundary : new long[] {Long.MAX_VALUE, -2, -1}) {
            tokens.set(boundary);
            String id = "e" + i++;
            long token = tracker.acquire(id);
            assertTrue(token > 0, "counter at " + boundary + " minted " + token);
            assertTrue(tracker.isRunning(id));
            tracker.release(id, token);
            assertFalse(tracker.isRunning(id), "and the token completes the acquisition");
        }
    }

    @Test
    void releaseSettledDropsAMarkerButNeverARunningAcquisition() {
        var tracker = new DefaultInFlightTracker(60_000);
        tracker.markSettled("settled", tracker.acquire("settled"));
        tracker.acquire("running");

        tracker.releaseSettled("settled");
        tracker.releaseSettled("running");

        assertFalse(tracker.isSettled("settled"), "a settled marker is dropped");
        assertTrue(tracker.tryAcquire("settled"), "so the event can be acquired again");
        assertTrue(tracker.isRunning("running"), "a running acquisition is left alone");
    }
}

package io.outbox.dispatch;

/**
 * Tracks in-flight events to prevent duplicate concurrent dispatch of the same event.
 *
 * @see DefaultInFlightTracker
 */
public interface InFlightTracker {

    /**
     * Attempts to acquire exclusive processing rights for the given event.
     *
     * @param eventId the event ID to acquire
     * @return {@code true} if acquired, {@code false} if already in flight
     */
    boolean tryAcquire(String eventId);

    /**
     * Releases processing rights, allowing the event to be dispatched again.
     *
     * @param eventId the event ID to release
     */
    void release(String eventId);
}

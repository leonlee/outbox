package io.outbox.model;

/**
 * Lifecycle states of an outbox event. Stored as integer codes in the database.
 *
 * <p>Typical transitions: {@code NEW → DONE}, {@code NEW → RETRY → DONE},
 * or {@code NEW → DEAD} (unroutable or max retries exceeded).
 */
public enum EventStatus {
    /**
     * Newly inserted, awaiting dispatch.
     */
    NEW(0),
    /**
     * Successfully processed.
     */
    DONE(1),
    /**
     * Failed but eligible for retry after a delay.
     */
    RETRY(2),
    /**
     * Permanently failed; no further retries.
     */
    DEAD(3);

    private final int code;

    EventStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}

package io.outbox.spi;

import java.sql.Connection;
import java.time.Instant;

/**
 * Deletes terminal outbox events (DONE and DEAD) older than a given cutoff.
 *
 * <p>The outbox is a transient buffer, not an event store. Events that have reached
 * a terminal state should be purged after a retention period to prevent table bloat.
 * If clients need to archive events for audit, they should do so in their
 * {@link io.outbox.EventListener}.
 *
 * <p>All methods receive an explicit {@link Connection} so the caller controls
 * transaction boundaries. Implementations live in the {@code outbox-jdbc} module.
 *
 * @see io.outbox.jdbc.purge.AbstractJdbcEventPurger
 */
public interface EventPurger {

    /**
     * Deletes terminal events (DONE + DEAD) created before the given cutoff.
     *
     * @param conn   the JDBC connection (caller controls transaction)
     * @param before delete events where {@code COALESCE(done_at, created_at) < before}
     * @param limit  maximum number of rows to delete in this batch
     * @return the number of rows actually deleted
     */
    int purge(Connection conn, Instant before, int limit);
}

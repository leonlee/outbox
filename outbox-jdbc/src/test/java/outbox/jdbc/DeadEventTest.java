package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.jdbc.store.H2OutboxStore;
import outbox.model.EventStatus;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadEventTest {

    private JdbcDataSource dataSource;
    private H2OutboxStore outboxStore;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(
                    "CREATE TABLE outbox_event (" +
                            "event_id VARCHAR(36) PRIMARY KEY," +
                            "event_type VARCHAR(128) NOT NULL," +
                            "aggregate_type VARCHAR(64)," +
                            "aggregate_id VARCHAR(128)," +
                            "tenant_id VARCHAR(64)," +
                            "payload CLOB NOT NULL," +
                            "headers CLOB," +
                            "status TINYINT NOT NULL," +
                            "attempts INT NOT NULL DEFAULT 0," +
                            "available_at TIMESTAMP NOT NULL," +
                            "created_at TIMESTAMP NOT NULL," +
                            "done_at TIMESTAMP," +
                            "last_error CLOB," +
                            "locked_by VARCHAR(128)," +
                            "locked_at TIMESTAMP" +
                            ")"
            );
        }

        outboxStore = new H2OutboxStore();
    }

    @Test
    void queryDeadReturnsOnlyDeadEvents() throws SQLException {
        String deadId = insertAndMarkDead("TestEvent", "Agg", "dead error");
        String doneId = insertAndMarkDone("TestEvent", "Agg");
        String newId = insertTestEvent("TestEvent", "Agg");

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> dead = outboxStore.queryDead(conn, null, null, 100);
            assertEquals(1, dead.size());
            assertEquals(deadId, dead.get(0).eventId());
        }
    }

    @Test
    void queryDeadFiltersByEventType() throws SQLException {
        insertAndMarkDead("UserCreated", "User", "error");
        insertAndMarkDead("OrderPlaced", "Order", "error");

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> dead = outboxStore.queryDead(conn, "UserCreated", null, 100);
            assertEquals(1, dead.size());
            assertEquals("UserCreated", dead.get(0).eventType());
        }
    }

    @Test
    void queryDeadFiltersByAggregateType() throws SQLException {
        insertAndMarkDead("TestEvent", "User", "error");
        insertAndMarkDead("TestEvent", "Order", "error");

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> dead = outboxStore.queryDead(conn, null, "Order", 100);
            assertEquals(1, dead.size());
            assertEquals("Order", dead.get(0).aggregateType());
        }
    }

    @Test
    void queryDeadFiltersByBothEventTypeAndAggregateType() throws SQLException {
        insertAndMarkDead("UserCreated", "User", "error");
        insertAndMarkDead("OrderPlaced", "Order", "error");
        insertAndMarkDead("UserCreated", "Order", "error");

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> dead = outboxStore.queryDead(conn, "UserCreated", "User", 100);
            assertEquals(1, dead.size());
            assertEquals("UserCreated", dead.get(0).eventType());
            assertEquals("User", dead.get(0).aggregateType());
        }
    }

    @Test
    void queryDeadRespectsLimit() throws SQLException {
        for (int i = 0; i < 5; i++) {
            insertAndMarkDead("TestEvent", "Agg", "error");
        }

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> dead = outboxStore.queryDead(conn, null, null, 3);
            assertEquals(3, dead.size());
        }
    }

    @Test
    void replayDeadResetsToNew() throws SQLException {
        String eventId = insertAndMarkDead("TestEvent", "Agg", "some error");

        try (Connection conn = dataSource.getConnection()) {
            int updated = outboxStore.replayDead(conn, eventId);
            assertEquals(1, updated);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, attempts, available_at, last_error, locked_by, locked_at " +
                            "FROM outbox_event WHERE event_id=?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.NEW.code(), rs.getInt("status"));
                assertEquals(0, rs.getInt("attempts"));
                assertNotNull(rs.getTimestamp("available_at"));
                assertNull(rs.getString("last_error"));
                assertNull(rs.getString("locked_by"));
                assertNull(rs.getTimestamp("locked_at"));
            }
        }
    }

    @Test
    void replayDeadIgnoresNonDeadEvent() throws SQLException {
        String doneId = insertAndMarkDone("TestEvent", "Agg");

        try (Connection conn = dataSource.getConnection()) {
            int updated = outboxStore.replayDead(conn, doneId);
            assertEquals(0, updated);
        }
    }

    @Test
    void replayDeadIgnoresNonExistentEvent() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            int updated = outboxStore.replayDead(conn, "non-existent-id");
            assertEquals(0, updated);
        }
    }

    @Test
    void countDeadReturnsCorrectCount() throws SQLException {
        insertAndMarkDead("TestEvent", "Agg", "error");
        insertAndMarkDead("TestEvent", "Agg", "error");
        insertAndMarkDead("OtherEvent", "Agg", "error");
        insertTestEvent("TestEvent", "Agg"); // NEW, should not count

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(3, outboxStore.countDead(conn, null));
            assertEquals(2, outboxStore.countDead(conn, "TestEvent"));
            assertEquals(1, outboxStore.countDead(conn, "OtherEvent"));
            assertEquals(0, outboxStore.countDead(conn, "NonExistent"));
        }
    }

    private String insertTestEvent(String eventType, String aggregateType) throws SQLException {
        EventEnvelope event = EventEnvelope.builder(eventType)
                .aggregateType(aggregateType)
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);
        }
        return event.eventId();
    }

    private String insertAndMarkDead(String eventType, String aggregateType, String error) throws SQLException {
        String eventId = insertTestEvent(eventType, aggregateType);
        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDead(conn, eventId, error);
        }
        return eventId;
    }

    private String insertAndMarkDone(String eventType, String aggregateType) throws SQLException {
        String eventId = insertTestEvent(eventType, aggregateType);
        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDone(conn, eventId);
        }
        return eventId;
    }
}

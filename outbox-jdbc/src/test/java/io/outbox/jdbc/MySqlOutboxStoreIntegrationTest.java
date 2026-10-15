package io.outbox.jdbc;

import io.outbox.EventEnvelope;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.MySqlOutboxStore;
import io.outbox.model.EventStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DockerAvailable
@Testcontainers
class MySqlOutboxStoreIntegrationTest extends AbstractOutboxStoreIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withDatabaseName("outbox_test")
            // Data dir in memory: on a VM-backed Docker disk InnoDB initialisation can outlast the
            // startup timeout (seen locally on OrbStack: >90s on disk, 12s on tmpfs).
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    private static final MySqlOutboxStore STORE = new MySqlOutboxStore();
    private static SimpleDataSource dataSource;

    @BeforeAll
    static void initSchema() throws Exception {
        dataSource = new SimpleDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        String schema = loadResource("/schema/mysql.sql");
        try (Connection conn = dataSource.getConnection()) {
            for (String stmt : schema.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    conn.createStatement().execute(trimmed);
                }
            }
        }
    }

    @BeforeEach
    void truncate() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("TRUNCATE TABLE outbox_event");
        }
    }

    @Override
    DataSource dataSource() {
        return dataSource;
    }

    @Override
    AbstractJdbcOutboxStore store() {
        return STORE;
    }

    @Override
    AbstractJdbcOutboxStore storeFor(String table) {
        return new MySqlOutboxStore(table);
    }

    @Override
    AbstractJdbcOutboxStore stampingStore(String owner) {
        return new MySqlOutboxStore("outbox_event", owner);
    }

    @Override
    void shortLockWait(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SET SESSION innodb_lock_wait_timeout = 1");
        }
    }

    @Override
    String timestampType(int digits) {
        return digits == 0 ? "DATETIME" : "DATETIME(" + digits + ")";
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream is = MySqlOutboxStoreIntegrationTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * A sweep release finds its row by primary key, so InnoDB locks that row alone — the property the
     * sweep relies on instead of one UPDATE over every pending row.
     */
    @Test
    void aSweepReleaseLocksOnlyItsRow() throws Exception {
        EventEnvelope event = EventEnvelope.ofJson("StampEvent", "{}");
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(true);
            stampingStore("pod-a").insertNew(conn, event);
            try (ResultSet rs = st.executeQuery("EXPLAIN UPDATE outbox_event SET locked_by=NULL, locked_at=NULL"
                    + " WHERE event_id='" + event.eventId() + "' AND locked_by='pod-a#x' AND locked_at=created_at"
                    + " AND status IN (" + EventStatus.NEW.code() + "," + EventStatus.RETRY.code() + ")")) {
                assertTrue(rs.next());
                assertEquals("PRIMARY", rs.getString("key"));
            }
        }
    }
}

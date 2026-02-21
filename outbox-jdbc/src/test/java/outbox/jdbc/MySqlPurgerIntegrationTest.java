package outbox.jdbc;

import outbox.EventEnvelope;
import outbox.jdbc.purge.MySqlAgeBasedPurger;
import outbox.jdbc.purge.MySqlEventPurger;
import outbox.jdbc.store.MySqlOutboxStore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DockerAvailable
@Testcontainers
class MySqlPurgerIntegrationTest {

  @Container
  static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
      .withDatabaseName("outbox_purge_test");

  private static SimpleDataSource dataSource;
  private final MySqlOutboxStore store = new MySqlOutboxStore();
  private final MySqlEventPurger purger = new MySqlEventPurger();
  private final MySqlAgeBasedPurger ageBasedPurger = new MySqlAgeBasedPurger();

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

  @Test
  void purgeTerminalOnly() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      EventEnvelope done = EventEnvelope.ofJson("Done", "{}");
      EventEnvelope dead = EventEnvelope.ofJson("Dead", "{}");
      EventEnvelope pending = EventEnvelope.ofJson("Pending", "{}");
      store.insertNew(conn, done);
      store.insertNew(conn, dead);
      store.insertNew(conn, pending);
      store.markDone(conn, done.eventId());
      store.markDead(conn, dead.eventId(), "err");

      int purged = purger.purge(conn, Instant.now().plusSeconds(60), 100);
      assertEquals(2, purged);

      // Pending event should still exist
      assertEquals(1, store.pollPending(conn, Instant.now(), java.time.Duration.ZERO, 10).size());
    }
  }

  @Test
  void purgeRespectsTimeCutoff() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      EventEnvelope e = EventEnvelope.ofJson("CutoffEvent", "{}");
      store.insertNew(conn, e);
      store.markDone(conn, e.eventId());

      // Cutoff in the past should not purge recently-done events
      int purged = purger.purge(conn, Instant.now().minusSeconds(60), 100);
      assertEquals(0, purged);

      // Cutoff in the future should purge
      purged = purger.purge(conn, Instant.now().plusSeconds(60), 100);
      assertEquals(1, purged);
    }
  }

  @Test
  void purgeRespectsBatchLimit() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      for (int i = 0; i < 5; i++) {
        EventEnvelope e = EventEnvelope.ofJson("BatchPurge", "{}");
        store.insertNew(conn, e);
        store.markDone(conn, e.eventId());
      }

      int purged = purger.purge(conn, Instant.now().plusSeconds(60), 3);
      assertEquals(3, purged);

      // 2 should remain
      purged = purger.purge(conn, Instant.now().plusSeconds(60), 100);
      assertEquals(2, purged);
    }
  }

  @Test
  void ageBasedPurgerDeletesAllStatuses() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      EventEnvelope done = EventEnvelope.ofJson("AgedDone", "{}");
      EventEnvelope pending = EventEnvelope.ofJson("AgedPending", "{}");
      EventEnvelope dead = EventEnvelope.ofJson("AgedDead", "{}");
      store.insertNew(conn, done);
      store.insertNew(conn, pending);
      store.insertNew(conn, dead);
      store.markDone(conn, done.eventId());
      store.markDead(conn, dead.eventId(), "err");

      // All 3 should be purged (age-based doesn't care about status)
      int purged = ageBasedPurger.purge(conn, Instant.now().plusSeconds(60), 100);
      assertEquals(3, purged);
    }
  }

  @Test
  void ageBasedPurgerRespectsLimit() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      for (int i = 0; i < 4; i++) {
        store.insertNew(conn, EventEnvelope.ofJson("AgedLimit", "{}"));
      }

      int purged = ageBasedPurger.purge(conn, Instant.now().plusSeconds(60), 2);
      assertEquals(2, purged);
    }
  }

  private static String loadResource(String path) throws IOException {
    try (InputStream is = MySqlPurgerIntegrationTest.class.getResourceAsStream(path)) {
      if (is == null) throw new IOException("Resource not found: " + path);
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

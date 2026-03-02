package io.outbox.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.MySqlOutboxStore;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

@DockerAvailable
@Testcontainers
class MySqlOutboxStoreIntegrationTest extends AbstractOutboxStoreIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("outbox_test");

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

    private static String loadResource(String path) throws IOException {
        try (InputStream is = MySqlOutboxStoreIntegrationTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

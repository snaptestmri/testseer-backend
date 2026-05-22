package io.testseer.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.cloud.gcp.pubsub.emulator-host=localhost:8085",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class DatabaseMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7");

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void serviceRegistryTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'service_registry'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "service_id", "org_id", "repo", "service_name",
                "module_type", "build_tool", "source_roots", "test_roots",
                "owner_team", "enabled", "metadata", "created_at", "updated_at"
        );
    }
}

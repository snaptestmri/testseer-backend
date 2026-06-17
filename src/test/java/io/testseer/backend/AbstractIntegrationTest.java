package io.testseer.backend;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.cloud.gcp.pubsub.emulator-host=localhost:8085",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
            "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
    }
)
@Testcontainers
@Import(KafkaTestConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withReuse(true);

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7").withReuse(true);
}

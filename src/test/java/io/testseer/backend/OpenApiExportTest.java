package io.testseer.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates {@code docs/openapi.yaml} from the running app's springdoc endpoint.
 * Run: {@code mvn test -Dtest=OpenApiExportTest}
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
            "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
    }
)
@Testcontainers
@Import(KafkaTestConfiguration.class)
class OpenApiExportTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    @Container @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Autowired TestRestTemplate http;

    @Test
    void exportOpenApiSpec() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs.yaml", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("openapi:").contains("TestSeer API");

        Path out = Path.of("docs/openapi.yaml");
        Files.createDirectories(out.getParent());
        Files.writeString(out, response.getBody());

        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.size(out)).isGreaterThan(1000);
    }
}

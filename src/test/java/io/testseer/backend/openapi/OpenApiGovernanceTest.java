package io.testseer.backend.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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

import io.testseer.backend.KafkaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when committed {@code docs/openapi.yaml} drifts from the live springdoc export.
 * Regenerate: {@code mvn test -Dtest=OpenApiExportTest}
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
            + "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,"
            + "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
    }
)
@Testcontainers
@Import(KafkaTestConfiguration.class)
class OpenApiGovernanceTest {

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
    void committedOpenApiMatchesLiveExport() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs.yaml", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotBlank();

        Path committed = Path.of("docs/openapi.yaml");
        assertThat(Files.exists(committed))
                .as("Committed openapi.yaml must exist — run OpenApiExportTest to generate")
                .isTrue();

        String live = normalize(response.getBody());
        String onDisk = normalize(Files.readString(committed));

        assertThat(onDisk)
                .as("docs/openapi.yaml is out of date — run: mvn test -Dtest=OpenApiExportTest")
                .isEqualTo(live);
    }

    /** Ignore trailing whitespace differences between export runs. */
    private static String normalize(String yaml) {
        return yaml.lines()
                .map(String::stripTrailing)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)
                .stripTrailing();
    }
}

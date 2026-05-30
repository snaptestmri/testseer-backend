package io.testseer.backend.webhook;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(
    topics = {KafkaTopicsConfig.TOPIC_PR, KafkaTopicsConfig.TOPIC_BATCH},
    partitions = 1
)
class WebhookKafkaIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired ServiceRegistryService registryService;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired GitHubSignatureValidator signatureValidator;

    @Test
    void pullRequestWebhook_publishesJobToprTopic() throws Exception {
        registryService.register(new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                "service", List.of("src/main/java"), List.of("src/test/java"), null
        ));

        String payload = """
                {
                  "action": "synchronize",
                  "number": 99,
                  "pull_request": { "head": { "sha": "deadbeef" } },
                  "repository": {
                    "owner": { "login": "acme" },
                    "name": "order-service"
                  },
                  "commits": [{
                    "modified": ["src/main/java/OrderController.java"]
                  }]
                }
                """;
        String sig = signatureValidator.computeSignature(payload);

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", sig)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted());

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-consumer-" + System.currentTimeMillis(), "false", broker);
        consumerProps.put("value.deserializer",
                "org.springframework.kafka.support.serializer.JsonDeserializer");
        consumerProps.put("spring.json.value.default.type",
                "io.testseer.backend.webhook.IngestionJob");

        try (Consumer<String, IngestionJob> consumer =
                     new DefaultKafkaConsumerFactory<String, IngestionJob>(consumerProps)
                             .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, KafkaTopicsConfig.TOPIC_PR);
            ConsumerRecord<String, IngestionJob> record =
                    KafkaTestUtils.getSingleRecord(consumer, KafkaTopicsConfig.TOPIC_PR, Duration.ofSeconds(5));

            assertThat(record.value().jobType()).isEqualTo("PR");
            assertThat(record.value().orgId()).isEqualTo("acme");
            assertThat(record.value().commitSha()).isEqualTo("deadbeef");
        }
    }
}

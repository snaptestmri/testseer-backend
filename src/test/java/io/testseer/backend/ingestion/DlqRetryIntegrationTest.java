package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "testseer.observability.retry-backoff-base-ms=50",
        "testseer.observability.max-job-attempts=3"
})
@Testcontainers
@EmbeddedKafka(
        topics = {
                KafkaTopicsConfig.TOPIC_PR,
                KafkaTopicsConfig.TOPIC_BATCH,
                KafkaTopicsConfig.TOPIC_DLQ
        },
        partitions = 1
)
class DlqRetryIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired KafkaJobPublisher publisher;
    @Autowired JdbcClient db;
    @Autowired EmbeddedKafkaBroker broker;

    @MockBean WorkerPipeline workerPipeline;

    String jobId;

    @BeforeEach
    void setUp() {
        jobId = "dlq-e2e-" + UUID.randomUUID();
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();
        db.sql("""
                INSERT INTO service_registry(service_id, org_id, repo, service_name, build_tool, enabled)
                VALUES ('svc-dlq', 'quotient', 'repo', 'svc', 'maven', true)
                """).update();
        db.sql("""
                INSERT INTO analysis_runs
                  (job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
                VALUES (:jobId, 'quotient', 'svc-dlq', 'sha1', 'PR', 'QUEUED', 1, :enq)
                """)
                .param("jobId", jobId)
                .param("enq", Timestamp.from(Instant.now()))
                .update();

        doThrow(new RuntimeException("forced pipeline failure"))
                .when(workerPipeline).process(any());
    }

    @Test
    void failedJob_exhaustsRetries_movesToDlqTopicAndDb() throws Exception {
        IngestionJob job = new IngestionJob(
                jobId, "PR", "quotient", "repo", "svc-dlq", "sha1",
                List.of("src/Main.java"), 1, Instant.now(), 1, null
        );
        publisher.publishPrJob(job);

        awaitDlqStatus(jobId, Duration.ofSeconds(20));

        assertThat(db.sql("SELECT attempt FROM analysis_runs WHERE job_id = :id")
                .param("id", jobId)
                .query(Integer.class)
                .single()).isEqualTo(3);

        try (Consumer<String, IngestionJob> consumer = dlqConsumer()) {
            consumer.subscribe(List.of(KafkaTopicsConfig.TOPIC_DLQ));
            ConsumerRecord<String, IngestionJob> record =
                    KafkaTestUtils.getSingleRecord(consumer, KafkaTopicsConfig.TOPIC_DLQ, Duration.ofSeconds(10));
            assertThat(record.value().jobId()).isEqualTo(jobId);
            assertThat(record.value().attempt()).isEqualTo(3);
        }
    }

    private void awaitDlqStatus(String id, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String status = db.sql("SELECT status FROM analysis_runs WHERE job_id = :id")
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if ("DLQ".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for analysis_runs.status=DLQ for job " + id);
    }

    private Consumer<String, IngestionJob> dlqConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "dlq-verify-" + UUID.randomUUID(), "true", broker);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.springframework.kafka.support.serializer.JsonDeserializer");
        props.put("spring.json.value.default.type", "io.testseer.backend.webhook.IngestionJob");
        props.put("spring.json.trusted.packages", "io.testseer.backend.webhook");
        return new DefaultKafkaConsumerFactory<String, IngestionJob>(props).createConsumer();
    }
}

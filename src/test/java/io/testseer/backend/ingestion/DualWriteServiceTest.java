package io.testseer.backend.ingestion;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class DualWriteServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired DualWriteService dualWriteService;
    @Autowired ServiceRegistryService registryService;
    @Autowired JdbcClient jdbcClient;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired AnalysisRunTracker runTracker;

    String serviceId;

    @BeforeEach
    void setup() {
        jdbcClient.sql("DELETE FROM analysis_runs").update();
        jdbcClient.sql("DELETE FROM outbound_call_facts").update();
        jdbcClient.sql("DELETE FROM symbol_facts").update();
        jdbcClient.sql("DELETE FROM peripheral_facts").update();
        jdbcClient.sql("DELETE FROM service_registry").update();
        mongoTemplate.dropCollection("parsed_models");

        serviceId = registryService.register(new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                "service", List.of("src/main/java"), null, null
        )).serviceId();
    }

    @Test
    void write_persists_symbolFacts_to_postgres() {
        FactBatch batch = new FactBatch(
                "job-001", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(new FactBatch.SymbolFact(
                        "OrderController.java", "com.example.OrderController",
                        "CLASS", "{}", "javaparser", 1.0
                )),
                List.of(), List.of(), List.of()
        );

        dualWriteService.write(batch, List.of());

        long count = jdbcClient
                .sql("SELECT COUNT(*) FROM symbol_facts WHERE service_id = :id")
                .param("id", serviceId)
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void write_persists_parsedModel_to_mongodb() {
        ParsedModel model = new ParsedModel(
                "OrderController.java", "com.example.OrderController",
                List.of("RestController"), List.of(), List.of(),
                List.of(), List.of(), false, null
        );
        FactBatch batch = new FactBatch(
                "job-002", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of()
        );

        dualWriteService.write(batch, List.of(model));

        long count = mongoTemplate.getCollection("parsed_models")
                .countDocuments(new Document("serviceId", serviceId));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void markComplete_sets_COMPLETE_status_in_analysis_runs() {
        jdbcClient.sql("""
            INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
            VALUES ('j-track-001', 'acme', :svcId, 'abc', 'PR', 'QUEUED', 1, now())
            """).param("svcId", serviceId).update();

        runTracker.markRunning("j-track-001");
        runTracker.markComplete("j-track-001");

        String status = jdbcClient.sql(
                "SELECT status FROM analysis_runs WHERE job_id = 'j-track-001'")
                .query(String.class).single();

        assertThat(status).isEqualTo("COMPLETE");
    }
}

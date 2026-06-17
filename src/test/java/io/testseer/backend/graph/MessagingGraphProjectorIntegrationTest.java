package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
})
@Testcontainers
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class MessagingGraphProjectorIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired MessagingGraphProjector messagingGraphProjector;
    @Autowired GraphEdgeRepository edgeRepo;
    @Autowired ServiceRegistryService registryService;
    @Autowired JdbcClient db;

    String serviceId;

    @BeforeEach
    void setup() {
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM service_registry").update();

        serviceId = registryService.register(new RegistrationRequest(
                "quotient", "offer-suite", "offer-suite", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null
        )).serviceId();
    }

    @Test
    void project_createsPublishAndGuardEdges() {
        FactBatch batch = FactBatch.create(
                "job-g-1", "quotient", "offer-suite", serviceId, "sha1", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(new FactBatch.PubSubResourceFact(
                        "TOPIC", "PDN_T.OFFER_UPDATE", "pdn", "pdn", null, null,
                        "PUBLISH", "offerUpdate", "application-pdn.yaml", "offer-publisher",
                        "com.example.OfferUpdatePublisher", "publish", null,
                        "YAML", 1.0, null)),
                List.of(),
                List.of(),
                List.of(new FactBatch.FlowGateFact(
                        "pdn", "com.example.OfferUpdatePublisher", "CPA_NOTIFY", null,
                        "CODE_FLAG", "notify.enabled", "true", "EQ", "SKIP", null,
                        "Enable notify", "YAML", null, 0.9)),
                List.of());

        messagingGraphProjector.project(batch);

        long publishEdges = db.sql("""
                SELECT COUNT(*) FROM graph_edges WHERE edge_type = 'PUBLISHES_TO'
                """).query(Long.class).single();
        long guardEdges = db.sql("""
                SELECT COUNT(*) FROM graph_edges WHERE edge_type = 'GUARDED_BY'
                """).query(Long.class).single();

        assertThat(publishEdges).isGreaterThanOrEqualTo(1);
        assertThat(guardEdges).isGreaterThanOrEqualTo(1);
    }
}

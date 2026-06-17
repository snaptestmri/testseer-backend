package io.testseer.backend.query;

import io.testseer.backend.graph.GraphEdge;
import io.testseer.backend.graph.GraphEdgeRepository;
import io.testseer.backend.graph.GraphNode;
import io.testseer.backend.graph.GraphNodeRepository;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
            "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
    }
)
@Testcontainers
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class QueryApiIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    @Container @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Autowired TestRestTemplate http;
    @Autowired ServiceRegistryService svcRegistry;
    @Autowired GraphNodeRepository nodeRepo;
    @Autowired GraphEdgeRepository edgeRepo;
    @Autowired JdbcClient db;

    String serviceId;

    @BeforeEach
    void setup() {
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM symbol_facts").update();
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();

        ServiceEntry svc = svcRegistry.register(new RegistrationRequest(
                "acme", "repo", "orders", "MAVEN", "service", null, null, null));
        serviceId = svc.serviceId();

        nodeRepo.upsert(GraphNode.service("svc-" + serviceId, "acme", "repo", "orders"));
        nodeRepo.upsert(GraphNode.service("svc-inventory", "acme", "repo", "inventory"));
        edgeRepo.insert(GraphEdge.calls("svc-" + serviceId, "svc-inventory"));

        db.sql("""
            INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha,
                job_type, status, attempt, enqueued_at, completed_at)
            VALUES ('j1', 'acme', :svcId, 'abc123', 'PR', 'COMPLETE', 1, now(), now())
            """).param("svcId", serviceId).update();
    }

    @Test
    void status_returns_CURRENT_for_recently_indexed_service() {
        ResponseEntity<Map> resp = http.getForEntity(
                "/v1/status/" + serviceId, Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("freshnessStatus")).isEqualTo("CURRENT");
    }

    @Test
    void graphReachability_returns_schemaVersioned_envelope() {
        ResponseEntity<Map> resp = http.getForEntity(
                "/v1/graph/reachability?serviceId=" + serviceId +
                "&type=service&orgId=acme&repo=repo",
                Map.class);

        assertThat(resp.getStatusCode().value()).isIn(200, 202);
        assertThat(resp.getBody().get("schemaVersion")).isEqualTo("1.0");
    }

    @Test
    void status_returns_NOT_INDEXED_for_unknown_service() {
        ResponseEntity<Map> resp = http.getForEntity("/v1/status/svc-unknown", Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("freshnessStatus")).isEqualTo("NOT_INDEXED");
    }
}

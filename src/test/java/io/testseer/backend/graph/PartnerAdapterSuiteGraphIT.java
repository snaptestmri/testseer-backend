package io.testseer.backend.graph;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PA-GAP-02 / PA-GAP-03 — partner-adapter factory routing and adapter reachability baseline.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                        + "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,"
                        + "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
        })
@Testcontainers
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class PartnerAdapterSuiteGraphIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired GraphProjectionService graphService;
    @Autowired GraphNodeRepository nodeRepo;
    @Autowired GraphEdgeRepository edgeRepo;
    @Autowired ServiceRegistryService svcRegistry;
    @Autowired JdbcClient db;
    @Autowired TestRestTemplate http;

    String serviceId;
    String consumerNodeId;

    @BeforeEach
    void setup() {
        ServiceEntry svc = PartnerAdapterGraphFixture.load(nodeRepo, edgeRepo, svcRegistry, db);
        serviceId = svc.serviceId();
        consumerNodeId = GraphNodeIds.classNode(serviceId, PartnerAdapterGraphFixture.CONSUMER_FQN);
    }

    @Test
    void classReachability_fromConsumer_includesAdapterChain() {
        ReachabilityResult result = graphService.classDependsOnClassForward(consumerNodeId);

        assertThat(result.nodes()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(result.edges()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.nodes()).extracting(GraphNode::symbolFqn)
                .contains(PartnerAdapterGraphFixture.HYVEE_ADAPTER_FQN,
                        PartnerAdapterGraphFixture.FACTORY_FQN);
    }

    @Test
    void restReachability_fromConsumer_returnsHydratedEnvelope() {
        ResponseEntity<Map> resp = http.getForEntity(
                "/v1/graph/reachability?serviceId=" + serviceId
                        + "&type=class&orgId=" + PartnerAdapterGraphFixture.ORG
                        + "&repo=" + PartnerAdapterGraphFixture.REPO
                        + "&symbolFqn=" + PartnerAdapterGraphFixture.CONSUMER_FQN,
                Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<?> nodes = (List<?>) data.get("nodes");
        List<?> edges = (List<?>) data.get("edges");
        assertThat(nodes).hasSizeGreaterThanOrEqualTo(4);
        assertThat(edges).hasSizeGreaterThanOrEqualTo(3);

        boolean hasHyvee = nodes.stream().anyMatch(raw -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) raw;
            return PartnerAdapterGraphFixture.HYVEE_ADAPTER_FQN.equals(node.get("symbolFqn"));
        });
        assertThat(hasHyvee).isTrue();
    }
}

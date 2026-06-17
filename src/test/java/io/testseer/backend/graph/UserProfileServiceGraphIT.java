package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactExtractor;
import io.testseer.backend.ingestion.JavaParserService;
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
 * BL-065 / UP-GAP-05 — Lombok {@code @RestController} reachability from {@code UserHistoryApiController}.
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
class UserProfileServiceGraphIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired GraphProjectionService graphService;
    @Autowired GraphFactProjector graphProjector;
    @Autowired FactExtractor factExtractor;
    @Autowired JavaParserService parser;
    @Autowired ServiceRegistryService svcRegistry;
    @Autowired JdbcClient db;
    @Autowired TestRestTemplate http;

    String serviceId;
    String controllerNodeId;

    @BeforeEach
    void setup() {
        ServiceEntry svc = UserProfileGraphFixture.load(
                parser, graphProjector, factExtractor, svcRegistry, db);
        serviceId = svc.serviceId();
        controllerNodeId = GraphNodeIds.classNode(serviceId, UserProfileGraphFixture.CONTROLLER);
    }

    @Test
    void classReachability_fromUserHistoryController_reachesShoppingService() {
        ReachabilityResult result = graphService.classDependsOnClassForward(controllerNodeId);

        assertThat(result.nodeIds()).isNotEmpty();
        assertThat(result.edges()).isNotEmpty();
        assertThat(result.nodes()).extracting(GraphNode::symbolFqn)
                .contains(UserProfileGraphFixture.SHOPPING_SERVICE,
                        UserProfileGraphFixture.SHOPPING_HELPER);
    }

    @Test
    void restReachability_fromController_returnsHydratedEnvelope() {
        ResponseEntity<Map> resp = http.getForEntity(
                "/v1/graph/reachability?serviceId=" + serviceId
                        + "&type=class&orgId=" + UserProfileGraphFixture.ORG
                        + "&repo=" + UserProfileGraphFixture.REPO
                        + "&symbolFqn=" + UserProfileGraphFixture.CONTROLLER,
                Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<?> nodes = (List<?>) data.get("nodes");
        List<?> edges = (List<?>) data.get("edges");
        assertThat(nodes).hasSizeGreaterThanOrEqualTo(2);
        assertThat(edges).isNotEmpty();
    }
}

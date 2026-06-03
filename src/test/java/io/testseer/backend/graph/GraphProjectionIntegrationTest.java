package io.testseer.backend.graph;

import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class GraphProjectionIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired GraphProjectionService graphService;
    @Autowired GraphNodeRepository nodeRepo;
    @Autowired GraphEdgeRepository edgeRepo;
    @Autowired ServiceRegistryService svcRegistry;
    @Autowired JdbcClient db;

    @BeforeEach
    void setup() {
        GraphFixture.load(nodeRepo, edgeRepo, svcRegistry, db);
    }

    @Test
    void forwardReachability_services_findsTransitiveCallees() {
        ReachabilityResult result = graphService.serviceCallsServiceForward("svc-orders");
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.nodeIds()).containsExactlyInAnyOrder("svc-inventory", "svc-notifications");
    }

    @Test
    void forwardReachability_classes_findsTransitiveDependencies() {
        ReachabilityResult result = graphService.classDependsOnClassForward("cls-order-ctrl");
        assertThat(result.nodeIds()).contains("cls-order-svc");
    }

    @Test
    void outboundTraversal_fromEndpoint_findsDownstreamEndpoints() {
        ReachabilityResult result = graphService.endpointCallsOutbound("ep-get-order");
        assertThat(result.nodeIds()).contains("cls-inv-svc");
    }

    @Test
    void reverseReachability_onSharedType_findsConsumingServices() {
        ReachabilityResult result = graphService.reverseReachability("type-order-dto");
        assertThat(result.nodeIds()).containsExactlyInAnyOrder("svc-orders", "svc-inventory");
    }

    @Test
    void immediateNeighborhood_returnsDepth1Only() {
        ReachabilityResult result = graphService.immediateNeighborhood("svc-orders");
        assertThat(result.nodeIds()).containsExactlyInAnyOrder("svc-inventory", "svc-notifications");
    }

    @Test
    void crossServiceBoundary_doesNotReturnStartServiceNodes() {
        ReachabilityResult result = graphService.crossServiceBoundary("ep-get-order");
        assertThat(result.nodeIds()).doesNotContain("svc-orders");
        assertThat(result.nodeIds()).contains("cls-inv-svc");
    }

    @Test
    void sharedTypeResolution_findsCanonicalLibraryDefinition() {
        ReachabilityResult result = graphService.sharedTypeResolution(
                "com.example.shared.OrderDto");
        assertThat(result.nodeIds()).containsExactly("type-order-dto");
    }

    @Test
    void typeUsageFanOut_findsAllConsumingServices() {
        ReachabilityResult result = graphService.typeUsageFanOut("com.example.shared.OrderDto");
        assertThat(result.size()).isEqualTo(2);
    }
}

package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
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
    @Autowired IncrementalEdgeUpdater edgeUpdater;
    @Autowired GraphFactProjector graphProjector;
    @Autowired io.testseer.backend.ingestion.FactExtractor factExtractor;

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
        assertThat(result.nodes()).isNotEmpty();
        assertThat(result.edges()).isNotEmpty();
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
        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void typeUsageFanOut_findsAllConsumingServices() {
        ReachabilityResult result = graphService.typeUsageFanOut("com.example.shared.OrderDto");
        assertThat(result.size()).isEqualTo(2);
    }

    @Test
    void incrementalUpdate_replacesEdgesForChangedNode() {
        // Before: cls-order-ctrl -> cls-order-svc (DEPENDS_ON)
        ReachabilityResult before = graphService.classDependsOnClassForward("cls-order-ctrl");
        assertThat(before.nodeIds()).contains("cls-order-svc");

        // Add a new class node for the replacement dependency
        nodeRepo.upsert(GraphNode.clazz("cls-payment-svc", GraphFixture.ORG, GraphFixture.REPO,
                "orders", "com.example.PaymentService"));

        // Update: cls-order-ctrl now depends on cls-payment-svc instead
        edgeUpdater.replaceEdges(
                "cls-order-ctrl",
                "DEPENDS_ON",
                List.of(GraphEdge.dependsOn("cls-order-ctrl", "cls-payment-svc"))
        );

        ReachabilityResult after = graphService.classDependsOnClassForward("cls-order-ctrl");
        assertThat(after.nodeIds()).doesNotContain("cls-order-svc");
        assertThat(after.nodeIds()).contains("cls-payment-svc");
    }

    @Test
    void graphFactProjector_projectsParsedModels_andReverseReachabilityWorks() {
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM service_registry").update();
        db.sql("DELETE FROM symbol_facts").update();

        var reg = svcRegistry.register(new RegistrationRequest(
                "acme", "orders-repo", "orders-svc", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));
        String serviceId = reg.serviceId();

        ParsedModel orderService = ParsedModel.of(
                "src/main/java/com/example/OrderService.java",
                "com.example.OrderService",
                List.of(), List.of(), List.of("OrderRepository"),
                List.of(), List.of(), false, null,
                null, List.of(), List.of());

        ParsedModel orderController = ParsedModel.of(
                "src/main/java/com/example/OrderController.java",
                "com.example.OrderController",
                List.of("RestController"),
                List.of("OrderService"), List.of(),
                List.of(new ParsedModel.EndpointDef("GET", "/orders/{id}", "getOrder")),
                List.of(), false, null,
                null, List.of(), List.of());

        var batch2Facts = factExtractor.extractSymbolFacts(orderService);
        FactBatch batchFull = FactBatch.core(
                "job-1", "acme", "orders-repo", serviceId, "abc123", "BASELINE",
                java.util.stream.Stream.concat(
                        factExtractor.extractSymbolFacts(orderController).stream(),
                        batch2Facts.stream()).toList(),
                List.of(), List.of(), List.of());

        graphProjector.project(batchFull, List.of(orderService, orderController));

        ReachabilityResult impact = graphService.reverseReachability(
                GraphNodeIds.classNode(serviceId, "com.example.OrderService"));
        assertThat(impact.nodeIds()).contains(
                GraphNodeIds.classNode(serviceId, "com.example.OrderController"));
    }

    @Test
    void graphTraversalMeetsSLO_under100ms() {
        long start = System.currentTimeMillis();
        graphService.serviceCallsServiceForward("svc-orders");
        graphService.classDependsOnClassForward("cls-order-ctrl");
        graphService.reverseReachability("type-order-dto");
        graphService.crossServiceBoundary("ep-get-order");
        long elapsed = System.currentTimeMillis() - start;

        // 4 traversals must complete in < 100ms combined (budget per SLO is 100ms per query)
        assertThat(elapsed).isLessThan(100);
    }
}

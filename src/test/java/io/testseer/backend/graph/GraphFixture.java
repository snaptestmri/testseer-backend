package io.testseer.backend.graph;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

public class GraphFixture {

    public static final String ORG  = "acme";
    public static final String REPO = "monorepo";

    public static void load(GraphNodeRepository nodeRepo,
                             GraphEdgeRepository edgeRepo,
                             ServiceRegistryService svcRegistry,
                             JdbcClient db) {

        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM service_registry").update();

        // Register 3 services
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "orders", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "inventory", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "notifications", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "shared-lib", "MAVEN", "library",
                List.of("src/main/java"), List.of("src/test/java"), null));

        // Service nodes
        nodeRepo.upsert(GraphNode.service("svc-orders",        ORG, REPO, "orders"));
        nodeRepo.upsert(GraphNode.service("svc-inventory",     ORG, REPO, "inventory"));
        nodeRepo.upsert(GraphNode.service("svc-notifications", ORG, REPO, "notifications"));

        // Class nodes in orders
        nodeRepo.upsert(GraphNode.clazz("cls-order-ctrl",  ORG, REPO, "orders",
                "com.example.OrderController"));
        nodeRepo.upsert(GraphNode.clazz("cls-order-svc",   ORG, REPO, "orders",
                "com.example.OrderService"));

        // Endpoint in orders
        nodeRepo.upsert(GraphNode.endpoint("ep-get-order", ORG, REPO, "orders",
                "com.example.OrderController#getOrder"));

        // Class in inventory
        nodeRepo.upsert(GraphNode.clazz("cls-inv-svc", ORG, REPO, "inventory",
                "com.example.InventoryService"));

        // Shared type
        nodeRepo.upsert(GraphNode.sharedType("type-order-dto", ORG, REPO, "shared-lib",
                "com.example.shared.OrderDto"));

        // Edges
        edgeRepo.insert(GraphEdge.calls("svc-orders", "svc-inventory"));
        edgeRepo.insert(GraphEdge.calls("svc-orders", "svc-notifications"));
        edgeRepo.insert(GraphEdge.dependsOn("cls-order-ctrl", "cls-order-svc"));
        edgeRepo.insert(GraphEdge.outboundTo("ep-get-order", "cls-inv-svc"));
        edgeRepo.insert(GraphEdge.usesType("svc-orders", "type-order-dto"));
        edgeRepo.insert(GraphEdge.usesType("svc-inventory", "type-order-dto"));
    }
}

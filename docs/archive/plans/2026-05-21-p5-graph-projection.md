# P5: Graph Projection Layer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the full graph projection layer: upsert/delete `graph_nodes` and `graph_edges`, and expose all 9 traversal queries from the Phase 0 spike as a `GraphProjectionService` backed by Postgres recursive CTEs. Incremental edge updates must complete atomically in under 5ms.

**Architecture:** `GraphNodeRepository` and `GraphEdgeRepository` own all JDBC writes. `GraphProjectionService` wraps all 9 CTE read queries. `IncrementalEdgeUpdater` handles delete-then-insert for a single changed file in one transaction. All integration tests use Testcontainers Postgres with a fixture graph loaded before each test.

**Tech Stack:** Java 21, JdbcClient (Spring 6.1), Postgres 16 recursive CTEs, Testcontainers PostgreSQL, JUnit 5

**Prerequisite:** P1 (V6 migration — `graph_nodes`/`graph_edges` exist).

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   └── graph/
│       ├── GraphNode.java                  (domain record)
│       ├── GraphEdge.java                  (domain record)
│       ├── ReachabilityResult.java         (query result record)
│       ├── GraphNodeRepository.java        (JDBC upsert/delete)
│       ├── GraphEdgeRepository.java        (JDBC insert/delete)
│       ├── GraphProjectionService.java     (all 9 CTE queries)
│       └── IncrementalEdgeUpdater.java     (atomic delete + insert)
└── test/java/io/testseer/backend/
    └── graph/
        ├── GraphProjectionIntegrationTest.java
        └── GraphFixture.java               (test fixture builder)
```

---

### Task 1: Domain records

**Files:**
- Create: `src/main/java/io/testseer/backend/graph/GraphNode.java`
- Create: `src/main/java/io/testseer/backend/graph/GraphEdge.java`
- Create: `src/main/java/io/testseer/backend/graph/ReachabilityResult.java`

- [ ] **Step 1: Create `GraphNode.java`**

```java
package io.testseer.backend.graph;

public record GraphNode(
        String id,
        String orgId,
        String repo,
        String service,
        String moduleType,    // "service" | "library"
        String nodeType,      // "SERVICE" | "CLASS" | "ENDPOINT" | "SHARED_TYPE"
        String symbolFqn
) {
    public static GraphNode service(String id, String orgId, String repo, String service) {
        return new GraphNode(id, orgId, repo, service, "service", "SERVICE", null);
    }

    public static GraphNode clazz(String id, String orgId, String repo,
                                   String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "service", "CLASS", fqn);
    }

    public static GraphNode endpoint(String id, String orgId, String repo,
                                      String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "service", "ENDPOINT", fqn);
    }

    public static GraphNode sharedType(String id, String orgId, String repo,
                                        String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "library", "SHARED_TYPE", fqn);
    }
}
```

- [ ] **Step 2: Create `GraphEdge.java`**

```java
package io.testseer.backend.graph;

public record GraphEdge(
        String fromNode,
        String toNode,
        String edgeType,    // "CALLS" | "DEPENDS_ON" | "OUTBOUND_TO" | "USES_TYPE"
        double confidence,
        String evidenceSource
) {
    public static GraphEdge calls(String from, String to) {
        return new GraphEdge(from, to, "CALLS", 1.0, "javaparser");
    }

    public static GraphEdge dependsOn(String from, String to) {
        return new GraphEdge(from, to, "DEPENDS_ON", 1.0, "javaparser");
    }

    public static GraphEdge outboundTo(String from, String to) {
        return new GraphEdge(from, to, "OUTBOUND_TO", 0.9, "javaparser");
    }

    public static GraphEdge usesType(String from, String to) {
        return new GraphEdge(from, to, "USES_TYPE", 1.0, "javaparser");
    }
}
```

- [ ] **Step 3: Create `ReachabilityResult.java`**

```java
package io.testseer.backend.graph;

import java.util.List;

public record ReachabilityResult(
        List<String> nodeIds,
        List<GraphNode> nodes
) {
    public int size() { return nodeIds.size(); }
    public boolean isEmpty() { return nodeIds.isEmpty(); }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/testseer/backend/graph/
git commit -m "feat: add GraphNode, GraphEdge, and ReachabilityResult records"
```

---

### Task 2: `GraphNodeRepository` and `GraphEdgeRepository`

**Files:**
- Create: `src/main/java/io/testseer/backend/graph/GraphNodeRepository.java`
- Create: `src/main/java/io/testseer/backend/graph/GraphEdgeRepository.java`
- Create: `src/test/java/io/testseer/backend/graph/GraphFixture.java`

- [ ] **Step 1: Create `GraphFixture.java`** (test helper, build before the repos)

```java
package io.testseer.backend.graph;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
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
                ORG, REPO, "orders", "MAVEN", "service", null, null, null));
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "inventory", "MAVEN", "service", null, null, null));
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "notifications", "MAVEN", "service", null, null, null));
        svcRegistry.register(new RegistrationRequest(
                ORG, REPO, "shared-lib", "MAVEN", "library", null, null, null));

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
```

- [ ] **Step 2: Create `GraphNodeRepository.java`**

```java
package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
public class GraphNodeRepository {

    private final JdbcClient db;

    public GraphNodeRepository(JdbcClient db) {
        this.db = db;
    }

    public void upsert(GraphNode node) {
        db.sql("""
                INSERT INTO graph_nodes(id, org_id, repo, service, module_type, node_type, symbol_fqn)
                VALUES (:id, :orgId, :repo, :service, :moduleType, :nodeType, :symbolFqn)
                ON CONFLICT (id) DO UPDATE SET
                  module_type = EXCLUDED.module_type,
                  node_type   = EXCLUDED.node_type,
                  symbol_fqn  = EXCLUDED.symbol_fqn
                """)
                .param("id",         node.id())
                .param("orgId",      node.orgId())
                .param("repo",       node.repo())
                .param("service",    node.service())
                .param("moduleType", node.moduleType())
                .param("nodeType",   node.nodeType())
                .param("symbolFqn",  node.symbolFqn())
                .update();
    }

    public void deleteByService(String orgId, String service) {
        db.sql("DELETE FROM graph_nodes WHERE org_id = :orgId AND service = :service")
                .param("orgId",   orgId)
                .param("service", service)
                .update();
    }

    private static GraphNode mapRow(ResultSet rs, int row) throws SQLException {
        return new GraphNode(
                rs.getString("id"), rs.getString("org_id"), rs.getString("repo"),
                rs.getString("service"), rs.getString("module_type"),
                rs.getString("node_type"), rs.getString("symbol_fqn")
        );
    }
}
```

- [ ] **Step 3: Create `GraphEdgeRepository.java`**

```java
package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GraphEdgeRepository {

    private final JdbcClient db;

    public GraphEdgeRepository(JdbcClient db) {
        this.db = db;
    }

    public void insert(GraphEdge edge) {
        db.sql("""
                INSERT INTO graph_edges(from_node, to_node, edge_type, confidence, evidence_source)
                VALUES (:from, :to, :edgeType, :confidence, :evidence)
                """)
                .param("from",       edge.fromNode())
                .param("to",         edge.toNode())
                .param("edgeType",   edge.edgeType())
                .param("confidence", edge.confidence())
                .param("evidence",   edge.evidenceSource())
                .update();
    }

    public int deleteFromNode(String fromNodeId, String edgeType) {
        return db.sql("""
                DELETE FROM graph_edges WHERE from_node = :from AND edge_type = :edgeType
                """)
                .param("from",     fromNodeId)
                .param("edgeType", edgeType)
                .update();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/testseer/backend/graph/GraphNodeRepository.java \
        src/main/java/io/testseer/backend/graph/GraphEdgeRepository.java \
        src/test/java/io/testseer/backend/graph/GraphFixture.java
git commit -m "feat: add GraphNodeRepository and GraphEdgeRepository"
```

---

### Task 3: `GraphProjectionService` — all 9 CTE queries

**Files:**
- Create: `src/main/java/io/testseer/backend/graph/GraphProjectionService.java`
- Create: `src/test/java/io/testseer/backend/graph/GraphProjectionIntegrationTest.java`

- [ ] **Step 1: Write failing integration tests**

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=GraphProjectionIntegrationTest -q
```

Expected: All 8 FAIL — `GraphProjectionService` does not exist.

- [ ] **Step 3: Create `GraphProjectionService.java`**

```java
package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class GraphProjectionService {

    private final JdbcClient db;

    public GraphProjectionService(JdbcClient db) {
        this.db = db;
    }

    public ReachabilityResult serviceCallsServiceForward(String serviceNodeId) {
        String sql = """
                WITH RECURSIVE reachable(node_id) AS (
                    SELECT to_node FROM graph_edges
                    WHERE from_node = :id AND edge_type = 'CALLS'
                    UNION
                    SELECT e.to_node FROM graph_edges e
                    JOIN reachable r ON e.from_node = r.node_id
                    WHERE e.edge_type = 'CALLS'
                )
                SELECT n.id FROM graph_nodes n
                JOIN reachable r ON n.id = r.node_id
                WHERE n.node_type = 'SERVICE'
                """;
        return query(sql, "id", serviceNodeId);
    }

    public ReachabilityResult classDependsOnClassForward(String classNodeId) {
        String sql = """
                WITH RECURSIVE reachable(node_id) AS (
                    SELECT to_node FROM graph_edges
                    WHERE from_node = :id AND edge_type = 'DEPENDS_ON'
                    UNION
                    SELECT e.to_node FROM graph_edges e
                    JOIN reachable r ON e.from_node = r.node_id
                    WHERE e.edge_type = 'DEPENDS_ON'
                )
                SELECT n.id FROM graph_nodes n
                JOIN reachable r ON n.id = r.node_id
                WHERE n.node_type = 'CLASS'
                """;
        return query(sql, "id", classNodeId);
    }

    public ReachabilityResult endpointCallsOutbound(String endpointNodeId) {
        String sql = """
                WITH RECURSIVE reachable(node_id) AS (
                    SELECT to_node FROM graph_edges
                    WHERE from_node = :id AND edge_type = 'OUTBOUND_TO'
                    UNION
                    SELECT e.to_node FROM graph_edges e
                    JOIN reachable r ON e.from_node = r.node_id
                    WHERE e.edge_type = 'OUTBOUND_TO'
                )
                SELECT n.id FROM graph_nodes n
                JOIN reachable r ON n.id = r.node_id
                """;
        return query(sql, "id", endpointNodeId);
    }

    public ReachabilityResult reverseReachability(String nodeId) {
        String sql = """
                WITH RECURSIVE affected(node_id) AS (
                    SELECT from_node FROM graph_edges
                    WHERE to_node = :id AND edge_type IN ('CALLS', 'DEPENDS_ON', 'USES_TYPE')
                    UNION
                    SELECT e.from_node FROM graph_edges e
                    JOIN affected a ON e.to_node = a.node_id
                    WHERE e.edge_type IN ('CALLS', 'DEPENDS_ON', 'USES_TYPE')
                )
                SELECT n.id FROM graph_nodes n
                JOIN affected a ON n.id = a.node_id
                """;
        return query(sql, "id", nodeId);
    }

    public ReachabilityResult immediateNeighborhood(String nodeId) {
        String sql = """
                SELECT n.id FROM graph_nodes n
                JOIN graph_edges e ON n.id = e.to_node
                WHERE e.from_node = :id
                  AND e.edge_type IN ('CALLS', 'DEPENDS_ON', 'OUTBOUND_TO')
                """;
        return query(sql, "id", nodeId);
    }

    public ReachabilityResult crossServiceBoundary(String startNodeId) {
        String sql = """
                WITH RECURSIVE
                    start_svc AS (
                        SELECT service FROM graph_nodes WHERE id = :id
                    ),
                    traversal(node_id) AS (
                        SELECT e.to_node
                        FROM graph_edges e
                        JOIN graph_nodes n_from ON e.from_node = n_from.id
                        JOIN graph_nodes n_to   ON e.to_node   = n_to.id
                        CROSS JOIN start_svc s
                        WHERE e.from_node = :id
                          AND e.edge_type IN ('OUTBOUND_TO', 'CALLS')
                          AND n_from.service <> n_to.service
                        UNION
                        SELECT e.to_node
                        FROM graph_edges e
                        JOIN traversal t ON e.from_node = t.node_id
                        WHERE e.edge_type IN ('OUTBOUND_TO', 'CALLS')
                    )
                SELECT DISTINCT t.node_id AS id
                FROM traversal t
                JOIN graph_nodes n ON t.node_id = n.id
                CROSS JOIN start_svc s
                WHERE n.service <> s.service
                """;
        return queryTwoParams(sql, startNodeId);
    }

    public ReachabilityResult sharedTypeResolution(String symbolFqn) {
        String sql = """
                SELECT id FROM graph_nodes
                WHERE symbol_fqn = :fqn AND module_type = 'library'
                """;
        List<String> ids = db.sql(sql).param("fqn", symbolFqn).query(String.class).list();
        return new ReachabilityResult(ids, List.of());
    }

    public ReachabilityResult typeUsageFanOut(String symbolFqn) {
        String sql = """
                SELECT DISTINCT n_consumer.id
                FROM graph_edges e
                JOIN graph_nodes n_shared   ON e.to_node   = n_shared.id
                JOIN graph_nodes n_consumer ON e.from_node = n_consumer.id
                WHERE n_shared.symbol_fqn = :fqn
                  AND n_shared.module_type = 'library'
                  AND e.edge_type = 'USES_TYPE'
                """;
        List<String> ids = db.sql(sql).param("fqn", symbolFqn).query(String.class).list();
        return new ReachabilityResult(ids, List.of());
    }

    private ReachabilityResult query(String sql, String paramName, String paramValue) {
        List<String> ids = db.sql(sql).param(paramName, paramValue).query(String.class).list();
        return new ReachabilityResult(ids, List.of());
    }

    private ReachabilityResult queryTwoParams(String sql, String nodeId) {
        List<String> ids = db.sql(sql)
                .param("id", nodeId)
                .query(String.class)
                .list();
        return new ReachabilityResult(ids, List.of());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=GraphProjectionIntegrationTest -q
```

Expected: All 8 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/graph/GraphProjectionService.java \
        src/test/java/io/testseer/backend/graph/GraphProjectionIntegrationTest.java \
        src/test/java/io/testseer/backend/graph/GraphFixture.java
git commit -m "feat: add GraphProjectionService with all 9 recursive CTE traversal queries"
```

---

### Task 4: `IncrementalEdgeUpdater`

**Files:**
- Create: `src/main/java/io/testseer/backend/graph/IncrementalEdgeUpdater.java`
- Modify: `src/test/java/io/testseer/backend/graph/GraphProjectionIntegrationTest.java`

- [ ] **Step 1: Add failing test for incremental update**

Add to `GraphProjectionIntegrationTest`:

```java
@Autowired IncrementalEdgeUpdater edgeUpdater;

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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=GraphProjectionIntegrationTest#incrementalUpdate_replacesEdgesForChangedNode -q
```

Expected: FAIL — `IncrementalEdgeUpdater` does not exist.

- [ ] **Step 3: Create `IncrementalEdgeUpdater.java`**

```java
package io.testseer.backend.graph;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class IncrementalEdgeUpdater {

    private final GraphEdgeRepository edgeRepo;

    public IncrementalEdgeUpdater(GraphEdgeRepository edgeRepo) {
        this.edgeRepo = edgeRepo;
    }

    @Transactional
    public void replaceEdges(String fromNodeId, String edgeType, List<GraphEdge> newEdges) {
        edgeRepo.deleteFromNode(fromNodeId, edgeType);
        newEdges.forEach(edgeRepo::insert);
    }
}
```

- [ ] **Step 4: Run all graph tests**

```bash
mvn test -Dtest=GraphProjectionIntegrationTest -q
```

Expected: All 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/graph/IncrementalEdgeUpdater.java \
        src/test/java/io/testseer/backend/graph/GraphProjectionIntegrationTest.java
git commit -m "feat: add IncrementalEdgeUpdater for atomic single-file edge replacement"
```

---

### Task 5: Verify Phase 0 spike latency SLO in integration

- [ ] **Step 1: Add latency assertion test**

Add to `GraphProjectionIntegrationTest`:

```java
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
```

- [ ] **Step 2: Run latency test**

```bash
mvn test -Dtest=GraphProjectionIntegrationTest#graphTraversalMeetsSLO_under100ms -q
```

Expected: PASS. (With Testcontainers Postgres on localhost, latency will be well under 100ms.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/testseer/backend/graph/GraphProjectionIntegrationTest.java
git commit -m "test: add graph traversal latency SLO assertion (4 queries < 100ms)"
```

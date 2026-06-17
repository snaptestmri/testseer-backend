package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.graph.GraphEdge;
import io.testseer.backend.graph.GraphEdgeRepository;
import io.testseer.backend.graph.GraphNode;
import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.graph.GraphNodeRepository;
import io.testseer.backend.graph.GraphRoutingService;
import io.testseer.backend.query.EntryFlowService;
import io.testseer.backend.query.PackagePrefixFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class FlowDiagramGraphExpander {

    private static final List<String> CODE_EDGE_TYPES =
            List.of("INVOKES", "ROUTES_TO", "DEPENDS_ON");
    private static final List<String> MESSAGING_EDGE_TYPES =
            List.of("PUBLISHES_TO", "SUBSCRIBES_TO", "OUTBOUND_TO");

    private final JdbcClient db;
    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;
    private final GraphRoutingService routingService;

    public FlowDiagramGraphExpander(
            JdbcClient db,
            GraphNodeRepository nodeRepo,
            GraphEdgeRepository edgeRepo,
            GraphRoutingService routingService) {
        this.db = db;
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
        this.routingService = routingService;
    }

    public record ExpansionResult(
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges
    ) {}

    public ExpansionResult expand(
            String orgId,
            String serviceId,
            List<String> startNodeIds,
            int depth,
            String packagePrefix,
            boolean includeExternalDomain) {

        Set<String> reachable = bfsReachable(startNodeIds, depth, CODE_EDGE_TYPES);
        reachable.addAll(startNodeIds);

        List<GraphNode> loaded = nodeRepo.findByIds(List.copyOf(reachable));
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (GraphNode node : loaded) {
            if (includeExternalDomain || PackagePrefixFilter.matchesNode(node, packagePrefix)) {
                nodes.put(node.id(), node);
            }
        }

        List<GraphEdge> edges = new ArrayList<>(edgeRepo.findEdgesBetween(nodes.keySet(), CODE_EDGE_TYPES).stream()
                .filter(e -> nodes.containsKey(e.fromNode()) && nodes.containsKey(e.toNode()))
                .toList());

        addRoutingFanOut(serviceId, nodes, edges);
        return new ExpansionResult(nodes, new ArrayList<>(edges));
    }

    public void addIngress(
            String orgId,
            String serviceId,
            EntryFlowService.EntryTriggerView trigger,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges) {
        if (trigger == null || trigger.linkedHandlerFqn() == null) {
            return;
        }
        String classNodeId = GraphNodeIds.classNode(serviceId, trigger.linkedHandlerFqn());
        String topicShortId = kafkaTopicFromPath(trigger.pathPattern());
        if (topicShortId == null) {
            return;
        }
        String envLane = trigger.envLane() != null ? trigger.envLane() : "unknown";
        String topicNodeId = orgId + ":topic:" + envLane + ":" + topicShortId;
        if (!nodes.containsKey(topicNodeId)) {
            nodes.put(topicNodeId, new GraphNode(
                    topicNodeId, orgId, null, serviceId,
                    "service", "TOPIC", topicShortId + "@" + envLane));
        }
        if (nodes.containsKey(classNodeId)) {
            edges.add(new GraphEdge(topicNodeId, classNodeId, "SUBSCRIBES_TO", 0.95, "ingress"));
        }
    }

    public void addMessagingEgress(
            String orgId,
            String serviceId,
            Map<String, GraphNode> nodes,
            List<GraphEdge> edges,
            String packagePrefix,
            boolean includeExternalDomain) {

        List<PubSubRow> rows = db.sql("""
                SELECT short_id, role, linked_class_fqn, env_lane, attributes
                FROM pubsub_resource_facts
                WHERE service_id = :svc
                  AND role IN ('PUBLISH', 'SUBSCRIBE')
                """)
                .param("svc", serviceId)
                .query((rs, row) -> new PubSubRow(
                        rs.getString("short_id"),
                        rs.getString("role"),
                        rs.getString("linked_class_fqn"),
                        rs.getString("env_lane"),
                        rs.getString("attributes")))
                .list();

        for (PubSubRow row : rows) {
            if (row.linkedClassFqn() == null) {
                continue;
            }
            String classNodeId = GraphNodeIds.classNode(serviceId, row.linkedClassFqn());
            if (!nodes.containsKey(classNodeId)) {
                if (!includeExternalDomain && !PackagePrefixFilter.matches(row.linkedClassFqn(), packagePrefix)) {
                    continue;
                }
                nodes.put(classNodeId, GraphNode.clazz(
                        classNodeId, orgId, null, serviceId, row.linkedClassFqn()));
            }
            String envLane = row.envLane() != null ? row.envLane() : "unknown";
            String topicNodeId = orgId + ":topic:" + envLane + ":" + row.shortId();
            String nodeType = "PUBLISH".equals(row.role()) ? "TOPIC" : "SUBSCRIPTION";
            if (!nodes.containsKey(topicNodeId)) {
                nodes.put(topicNodeId, new GraphNode(
                        topicNodeId, orgId, null, serviceId,
                        "service", nodeType, row.shortId() + "@" + envLane));
            }
            String edgeType = "PUBLISH".equals(row.role()) ? "PUBLISHES_TO" : "SUBSCRIBES_TO";
            edges.add(new GraphEdge(classNodeId, topicNodeId, edgeType, 0.9, "messaging"));
        }

        Set<String> linkedTopics = new LinkedHashSet<>();
        for (GraphNode n : nodes.values()) {
            if ("TOPIC".equals(n.nodeType()) && n.symbolFqn() != null) {
                linkedTopics.add(n.symbolFqn().split("@")[0]);
            }
        }
        // also collect from pubsub rows
        for (PubSubRow row : rows) {
            if (row.linkedClassFqn() != null && "PUBLISH".equals(row.role())) {
                linkedTopics.add(row.shortId());
            }
        }
    }

    public Set<String> linkedPublishTopics(String serviceId) {
        return new LinkedHashSet<>(db.sql("""
                SELECT DISTINCT short_id
                FROM pubsub_resource_facts
                WHERE service_id = :svc
                  AND role = 'PUBLISH'
                  AND linked_class_fqn IS NOT NULL
                """)
                .param("svc", serviceId)
                .query(String.class)
                .list());
    }

    private void addRoutingFanOut(
            String serviceId, Map<String, GraphNode> nodes, List<GraphEdge> edges) {
        GraphRoutingService.RoutingReport report = routingService.queryRouting(serviceId, null);
        for (GraphRoutingService.FactoryRoutingView factory : report.factories()) {
            if (!nodes.containsKey(factory.nodeId())) {
                continue;
            }
            for (GraphRoutingService.RouteEntry route : factory.routes()) {
                if (!nodes.containsKey(route.targetNodeId())) {
                    nodes.put(route.targetNodeId(), GraphNode.clazz(
                            route.targetNodeId(), nodes.get(factory.nodeId()).orgId(),
                            null, serviceId, route.targetClassFqn()));
                }
                String label = route.routingKey() + (route.fallback() ? "|fallback" : "");
                edges.add(new GraphEdge(
                        factory.nodeId(), route.targetNodeId(), "ROUTES_TO", 0.95, label));
            }
        }
    }

    private Set<String> bfsReachable(List<String> startIds, int maxDepth, List<String> edgeTypes) {
        if (startIds == null || startIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        int depth = maxDepth > 0 ? maxDepth : 6;
        String edgeIn = String.join("', '", edgeTypes);
        String sql = """
                WITH RECURSIVE reachable(node_id, depth) AS (
                    SELECT unnest(CAST(:starts AS text[])), 0
                    UNION
                    SELECT e.to_node, r.depth + 1
                    FROM graph_edges e
                    JOIN reachable r ON e.from_node = r.node_id
                    WHERE e.edge_type IN ('"""
                + edgeIn + """
                    ')
                      AND r.depth < :maxDepth
                )
                SELECT DISTINCT node_id FROM reachable
                """;
        List<String> ids = db.sql(sql)
                .param("starts", startIds.toArray(String[]::new))
                .param("maxDepth", depth)
                .query(String.class)
                .list();
        return new LinkedHashSet<>(ids);
    }

    private static String kafkaTopicFromPath(String pathPattern) {
        if (pathPattern == null || pathPattern.isBlank()) {
            return null;
        }
        String trimmed = pathPattern.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }
        return null;
    }

    private record PubSubRow(
            String shortId, String role, String linkedClassFqn, String envLane, String attributes) {}
}

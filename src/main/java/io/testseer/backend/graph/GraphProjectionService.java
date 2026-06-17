package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GraphProjectionService {

    private static final String CLASS_EDGE_TYPES = "'DEPENDS_ON', 'INVOKES', 'ROUTES_TO', 'IMPLEMENTS'";
    private static final String METHOD_EDGE_TYPES = "'INVOKES', 'ROUTES_TO'";
    private static final String IMPACT_EDGE_TYPES =
            "'CALLS', 'DEPENDS_ON', 'USES_TYPE', 'INVOKES', 'ROUTES_TO'";

    private final JdbcClient db;
    private final GraphSubgraphHydrator hydrator;

    public GraphProjectionService(JdbcClient db, GraphSubgraphHydrator hydrator) {
        this.db = db;
        this.hydrator = hydrator;
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
        return hydrate(serviceNodeId, queryIds(sql, "id", serviceNodeId));
    }

    public ReachabilityResult classDependsOnClassForward(String classNodeId) {
        String sql = """
                WITH RECURSIVE reachable(node_id) AS (
                    SELECT to_node FROM graph_edges
                    WHERE from_node = :id AND edge_type IN ("""
                + CLASS_EDGE_TYPES + """
                    )
                    UNION
                    SELECT e.to_node FROM graph_edges e
                    JOIN reachable r ON e.from_node = r.node_id
                    WHERE e.edge_type IN ("""
                + CLASS_EDGE_TYPES + """
                    )
                )
                SELECT n.id FROM graph_nodes n
                JOIN reachable r ON n.id = r.node_id
                WHERE n.node_type IN ('CLASS', 'METHOD')
                """;
        return hydrate(classNodeId, queryIds(sql, "id", classNodeId));
    }

    public ReachabilityResult methodForward(String methodNodeId, int maxDepth) {
        int depth = maxDepth > 0 ? maxDepth : 6;
        String sql = """
                WITH RECURSIVE reachable(node_id, depth) AS (
                    SELECT to_node, 1 FROM graph_edges
                    WHERE from_node = :id AND edge_type IN ("""
                + METHOD_EDGE_TYPES + """
                    )
                    UNION
                    SELECT e.to_node, r.depth + 1 FROM graph_edges e
                    JOIN reachable r ON e.from_node = r.node_id
                    WHERE e.edge_type IN ("""
                + METHOD_EDGE_TYPES + """
                    ) AND r.depth < :maxDepth
                )
                SELECT DISTINCT n.id FROM graph_nodes n
                JOIN reachable r ON n.id = r.node_id
                WHERE n.node_type IN ('CLASS', 'METHOD')
                """;
        List<String> ids = db.sql(sql)
                .param("id", methodNodeId)
                .param("maxDepth", depth)
                .query(String.class)
                .list();
        return hydrate(methodNodeId, ids);
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
        return hydrate(endpointNodeId, queryIds(sql, "id", endpointNodeId));
    }

    public ReachabilityResult reverseReachability(String nodeId) {
        String sql = """
                WITH RECURSIVE affected(node_id) AS (
                    SELECT from_node FROM graph_edges
                    WHERE to_node = :id AND edge_type IN ("""
                + IMPACT_EDGE_TYPES + """
                    )
                    UNION
                    SELECT e.from_node FROM graph_edges e
                    JOIN affected a ON e.to_node = a.node_id
                    WHERE e.edge_type IN ("""
                + IMPACT_EDGE_TYPES + """
                    )
                )
                SELECT n.id FROM graph_nodes n
                JOIN affected a ON n.id = a.node_id
                """;
        return hydrate(nodeId, queryIds(sql, "id", nodeId));
    }

    public ReachabilityResult immediateNeighborhood(String nodeId) {
        String sql = """
                SELECT DISTINCT n.id FROM graph_nodes n
                JOIN graph_edges e ON (n.id = e.to_node AND e.from_node = :id)
                                   OR (n.id = e.from_node AND e.to_node = :id)
                WHERE n.id <> :id
                  AND e.edge_type IN ('CALLS', 'DEPENDS_ON', 'OUTBOUND_TO', 'INVOKES',
                                      'ROUTES_TO', 'SUBSCRIBES_TO', 'PUBLISHES_TO')
                """;
        return hydrate(nodeId, queryIds(sql, "id", nodeId));
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
        return hydrate(startNodeId, queryTwoParamIds(sql, startNodeId));
    }

    public ReachabilityResult sharedTypeResolution(String symbolFqn) {
        String sql = """
                SELECT id FROM graph_nodes
                WHERE symbol_fqn = :fqn AND module_type = 'library'
                """;
        List<String> ids = db.sql(sql).param("fqn", symbolFqn).query(String.class).list();
        return idsOnly(ids);
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
        return idsOnly(ids);
    }

    private ReachabilityResult hydrate(String anchorNodeId, List<String> reachableIds) {
        return hydrator.hydrate(anchorNodeId, reachableIds);
    }

    private static ReachabilityResult idsOnly(List<String> ids) {
        return new ReachabilityResult(ids, List.of(), List.of());
    }

    private List<String> queryIds(String sql, String paramName, String paramValue) {
        return db.sql(sql).param(paramName, paramValue).query(String.class).list();
    }

    private List<String> queryTwoParamIds(String sql, String nodeId) {
        return db.sql(sql).param("id", nodeId).query(String.class).list();
    }
}

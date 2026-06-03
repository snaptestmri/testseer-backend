package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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

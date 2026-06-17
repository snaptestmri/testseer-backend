package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

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

    public List<GraphEdge> findEdgesBetween(Collection<String> nodeIds, Collection<String> edgeTypes) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        var spec = db.sql("""
                SELECT from_node, to_node, edge_type, confidence, evidence_source
                FROM graph_edges
                WHERE from_node IN (:nodeIds) AND to_node IN (:nodeIds)
                """)
                .param("nodeIds", nodeIds);
        if (edgeTypes != null && !edgeTypes.isEmpty()) {
            spec = db.sql("""
                    SELECT from_node, to_node, edge_type, confidence, evidence_source
                    FROM graph_edges
                    WHERE from_node IN (:nodeIds) AND to_node IN (:nodeIds)
                      AND edge_type IN (:edgeTypes)
                    """)
                    .param("nodeIds", nodeIds)
                    .param("edgeTypes", edgeTypes);
        }
        return spec.query(GraphEdgeRepository::mapRow).list();
    }

    private static GraphEdge mapRow(ResultSet rs, int row) throws SQLException {
        return new GraphEdge(
                rs.getString("from_node"),
                rs.getString("to_node"),
                rs.getString("edge_type"),
                rs.getDouble("confidence"),
                rs.getString("evidence_source"));
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

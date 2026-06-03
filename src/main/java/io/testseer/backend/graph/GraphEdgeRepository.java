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

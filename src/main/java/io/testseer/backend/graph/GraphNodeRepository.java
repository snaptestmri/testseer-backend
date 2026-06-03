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

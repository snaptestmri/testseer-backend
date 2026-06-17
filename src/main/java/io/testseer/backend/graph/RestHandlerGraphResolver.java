package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * GRP-19 / TRG-15: resolve API interface class nodes to {@code @RestController} implementations
 * for reachability and flow-diagram anchors.
 */
@Component
public class RestHandlerGraphResolver {

    private final JdbcClient db;

    public RestHandlerGraphResolver(JdbcClient db) {
        this.db = db;
    }

    /**
     * Returns the implementation class FQN when {@code symbolFqn} is an interface with an
     * {@code IMPLEMENTS} edge; otherwise returns {@code symbolFqn} unchanged.
     */
    public String resolveImplementationClassFqn(String serviceId, String symbolFqn) {
        if (serviceId == null || symbolFqn == null || symbolFqn.isBlank()) {
            return symbolFqn;
        }
        String classFqn = symbolFqn.contains("#")
                ? symbolFqn.substring(0, symbolFqn.indexOf('#'))
                : symbolFqn;
        return findImplementationFqn(serviceId, classFqn).orElse(classFqn);
    }

    public String resolveClassNodeId(String serviceId, String symbolFqn) {
        String resolvedFqn = resolveImplementationClassFqn(serviceId, symbolFqn);
        return GraphNodeIds.classNode(serviceId, resolvedFqn);
    }

    private Optional<String> findImplementationFqn(String serviceId, String interfaceFqn) {
        String fromNode = GraphNodeIds.classNode(serviceId, interfaceFqn);
        List<String> implFqns = db.sql("""
                SELECT gn.symbol_fqn
                FROM graph_edges e
                JOIN graph_nodes gn ON gn.id = e.to_node
                WHERE e.from_node = :fromNode
                  AND e.edge_type = 'IMPLEMENTS'
                  AND gn.node_type = 'CLASS'
                ORDER BY gn.symbol_fqn
                LIMIT 1
                """)
                .param("fromNode", fromNode)
                .query(String.class)
                .list();
        return implFqns.isEmpty() ? Optional.empty() : Optional.of(implFqns.get(0));
    }
}

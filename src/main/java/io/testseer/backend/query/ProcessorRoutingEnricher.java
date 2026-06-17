package io.testseer.backend.query;

import io.testseer.backend.graph.GraphRoutingService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Enriches entry-flow with processor factory routing discovered at index time. */
@Service
public class ProcessorRoutingEnricher {

    private final JdbcClient db;
    private final GraphRoutingService graphRoutingService;

    public ProcessorRoutingEnricher(JdbcClient db, GraphRoutingService graphRoutingService) {
        this.db = db;
        this.graphRoutingService = graphRoutingService;
    }

    public List<ProcessorRoutingStep> enrichForHandler(String serviceId, String handlerClassFqn) {
        if (serviceId == null || handlerClassFqn == null || handlerClassFqn.isBlank()) {
            return List.of();
        }

        List<String> factoryFqns = db.sql("""
                SELECT DISTINCT r.factory_class_fqn
                FROM routing_table_facts r
                WHERE r.service_id = :serviceId
                  AND (
                    r.factory_class_fqn IN (
                        SELECT gn_to.symbol_fqn
                        FROM graph_edges e
                        JOIN graph_nodes gn_from ON gn_from.id = e.from_node
                        JOIN graph_nodes gn_to   ON gn_to.id   = e.to_node
                        WHERE gn_from.symbol_fqn = :handlerFqn
                          AND gn_from.id LIKE :servicePrefix
                          AND e.edge_type = 'INVOKES'
                    )
                    OR r.factory_class_fqn IN (
                        SELECT gn_mid.symbol_fqn
                        FROM graph_edges e1
                        JOIN graph_nodes gn_from ON gn_from.id = e1.from_node
                        JOIN graph_nodes gn_mid  ON gn_mid.id  = e1.to_node
                        JOIN graph_edges e2      ON e2.from_node = gn_mid.id
                        JOIN graph_nodes gn_to   ON gn_to.id   = e2.to_node
                        WHERE gn_from.symbol_fqn = :handlerFqn
                          AND gn_from.id LIKE :servicePrefix
                          AND e1.edge_type = 'INVOKES'
                          AND e2.edge_type = 'INVOKES'
                          AND gn_to.symbol_fqn LIKE '%Factory'
                    )
                  )
                ORDER BY r.factory_class_fqn
                """)
                .param("serviceId", serviceId)
                .param("handlerFqn", handlerClassFqn)
                .param("servicePrefix", serviceId + "::%")
                .query(String.class)
                .list();

        if (factoryFqns.isEmpty()) {
            factoryFqns = db.sql("""
                    SELECT DISTINCT factory_class_fqn
                    FROM routing_table_facts
                    WHERE service_id = :serviceId
                    ORDER BY factory_class_fqn
                    """)
                    .param("serviceId", serviceId)
                    .query(String.class)
                    .list();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<ProcessorRoutingStep> steps = new ArrayList<>();
        for (String factoryFqn : factoryFqns) {
            if (!seen.add(factoryFqn)) {
                continue;
            }
            GraphRoutingService.RoutingReport report =
                    graphRoutingService.queryRouting(serviceId, factoryFqn);
            for (GraphRoutingService.FactoryRoutingView factory : report.factories()) {
                steps.add(new ProcessorRoutingStep(
                        "PROCESSOR_ROUTING",
                        factory.factoryFqn(),
                        factory.selectorMethod(),
                        factory.discriminatorType(),
                        factory.routes().stream()
                                .map(GraphRoutingService.RouteEntry::targetClassFqn)
                                .toList()));
            }
        }
        return steps;
    }

    public record ProcessorRoutingStep(
            String kind,
            String factoryFqn,
            String selectorMethod,
            String discriminatorType,
            List<String> possibleProcessors
    ) {}
}

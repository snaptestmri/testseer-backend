package io.testseer.backend.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GraphRoutingService {

    private final JdbcClient db;

    public GraphRoutingService(JdbcClient db) {
        this.db = db;
    }

    public RoutingReport queryRouting(String serviceId, String factoryFqn) {
        StringBuilder sql = new StringBuilder("""
                SELECT factory_class_fqn, selector_method, discriminator_type,
                       routing_key, target_bean, target_class_fqn, fallback
                FROM routing_table_facts
                WHERE service_id = :serviceId
                """);
        if (factoryFqn != null && !factoryFqn.isBlank()) {
            sql.append(" AND factory_class_fqn = :factoryFqn");
        }
        sql.append(" ORDER BY factory_class_fqn, routing_key");

        var spec = db.sql(sql.toString()).param("serviceId", serviceId);
        if (factoryFqn != null && !factoryFqn.isBlank()) {
            spec = spec.param("factoryFqn", factoryFqn);
        }

        Map<String, FactoryAccumulator> byFactory = new LinkedHashMap<>();
        spec.query((rs, row) -> {
            try {
                String factory = rs.getString("factory_class_fqn");
                String selectorMethod = rs.getString("selector_method");
                String discriminatorType = rs.getString("discriminator_type");
                String routingKey = rs.getString("routing_key");
                String targetBean = rs.getString("target_bean");
                String targetClassFqn = rs.getString("target_class_fqn");
                boolean fallback = rs.getBoolean("fallback");

                FactoryAccumulator acc = byFactory.computeIfAbsent(factory, f -> new FactoryAccumulator(
                        f,
                        GraphNodeIds.classNode(serviceId, f),
                        selectorMethod,
                        discriminatorType));
                acc.routes.add(new RouteEntry(
                        routingKey,
                        targetBean,
                        targetClassFqn,
                        GraphNodeIds.classNode(serviceId, targetClassFqn),
                        fallback));
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
            return null;
        }).list();

        if (byFactory.isEmpty()) {
            return new RoutingReport(serviceId, factoryFqn, List.of());
        }
        return new RoutingReport(serviceId, factoryFqn, byFactory.values().stream()
                .map(a -> new FactoryRoutingView(
                        a.factoryFqn,
                        a.nodeId,
                        a.selectorMethod,
                        a.discriminatorType,
                        List.copyOf(a.routes)))
                .toList());
    }

    private static final class FactoryAccumulator {
        final String factoryFqn;
        final String nodeId;
        final String selectorMethod;
        final String discriminatorType;
        final List<RouteEntry> routes = new ArrayList<>();

        FactoryAccumulator(String factoryFqn, String nodeId, String selectorMethod, String discriminatorType) {
            this.factoryFqn = factoryFqn;
            this.nodeId = nodeId;
            this.selectorMethod = selectorMethod;
            this.discriminatorType = discriminatorType;
        }
    }

    public record RoutingReport(
            String serviceId,
            String factoryFqnFilter,
            List<FactoryRoutingView> factories
    ) {}

    public record FactoryRoutingView(
            String factoryFqn,
            String nodeId,
            String selectorMethod,
            String discriminatorType,
            List<RouteEntry> routes
    ) {}

    public record RouteEntry(
            String routingKey,
            String targetBean,
            String targetClassFqn,
            String targetNodeId,
            boolean fallback
    ) {}
}

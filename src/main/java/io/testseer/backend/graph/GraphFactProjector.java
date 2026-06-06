package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.query.IndexCompleteNotifier;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class GraphFactProjector {

    private static final Logger log = LoggerFactory.getLogger(GraphFactProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final IncrementalEdgeUpdater edgeUpdater;
    private final ServiceRegistryService registryService;
    private final JdbcClient db;
    private final IndexCompleteNotifier indexCompleteNotifier;

    public GraphFactProjector(GraphNodeRepository nodeRepo,
                              IncrementalEdgeUpdater edgeUpdater,
                              ServiceRegistryService registryService,
                              JdbcClient db,
                              IndexCompleteNotifier indexCompleteNotifier) {
        this.nodeRepo               = nodeRepo;
        this.edgeUpdater            = edgeUpdater;
        this.registryService        = registryService;
        this.db                     = db;
        this.indexCompleteNotifier  = indexCompleteNotifier;
    }

    @Transactional
    public void project(FactBatch batch, List<ParsedModel> models) {
        ServiceEntry svc = registryService.getById(batch.serviceId());
        upsertServiceNode(svc);

        for (ParsedModel model : models) {
            if (model.classFqn() == null || model.parseError()) continue;
            projectModel(batch, svc, model);
        }

        refreshServiceCallEdges(batch.serviceId(), svc.serviceName());
        indexCompleteNotifier.notifyComplete(batch.orgId(), batch.repo(), batch.serviceId());
        log.debug("Graph projection complete for service {} ({} models)", batch.serviceId(), models.size());
    }

    private void upsertServiceNode(ServiceEntry svc) {
        String moduleType = "library".equals(svc.moduleType()) ? "library" : "service";
        nodeRepo.upsert(new GraphNode(
                GraphNodeIds.serviceNode(svc.serviceId()),
                svc.orgId(),
                svc.repo(),
                svc.serviceName(),
                moduleType,
                "SERVICE",
                null
        ));
    }

    private void projectModel(FactBatch batch, ServiceEntry svc, ParsedModel model) {
        String classNodeId = GraphNodeIds.classNode(batch.serviceId(), model.classFqn());
        nodeRepo.upsert(GraphNode.clazz(
                classNodeId, svc.orgId(), svc.repo(), svc.serviceName(), model.classFqn()));

        List<GraphEdge> dependsOnEdges = new ArrayList<>();
        List<GraphEdge> usesTypeEdges = new ArrayList<>();
        Set<String> depTypes = new HashSet<>();
        depTypes.addAll(model.constructorParamTypes());
        depTypes.addAll(model.fieldInjectionTypes());

        for (String depType : depTypes) {
            String depFqn = resolveTypeFqn(depType, model.classFqn());
            if (depFqn.isBlank() || depFqn.equals(model.classFqn())) continue;

            Optional<String> libraryNode = findLibraryTypeNode(depFqn);
            if (libraryNode.isPresent()) {
                usesTypeEdges.add(GraphEdge.usesType(classNodeId, libraryNode.get()));
                continue;
            }

            String depNodeId = GraphNodeIds.classNode(batch.serviceId(), depFqn);
            nodeRepo.upsert(GraphNode.clazz(
                    depNodeId, svc.orgId(), svc.repo(), svc.serviceName(), depFqn));
            dependsOnEdges.add(GraphEdge.dependsOn(classNodeId, depNodeId));
        }

        edgeUpdater.replaceEdges(classNodeId, "DEPENDS_ON", dependsOnEdges);
        edgeUpdater.replaceEdges(classNodeId, "USES_TYPE", usesTypeEdges);

        List<GraphEdge> outboundEdges = new ArrayList<>();
        for (ParsedModel.EndpointDef ep : model.endpoints()) {
            String endpointFqn = model.classFqn() + "#" + ep.methodName();
            String endpointNodeId = GraphNodeIds.endpointNode(batch.serviceId(), endpointFqn);
            nodeRepo.upsert(GraphNode.endpoint(
                    endpointNodeId, svc.orgId(), svc.repo(), svc.serviceName(), endpointFqn));
        }

        for (ParsedModel.OutboundCallDef call : model.outboundCalls()) {
            findEndpointNodeByHttp(call.httpMethod(), call.path())
                    .ifPresent(targetId -> outboundEdges.add(
                            GraphEdge.outboundTo(classNodeId, targetId)));
        }
        edgeUpdater.replaceEdges(classNodeId, "OUTBOUND_TO", outboundEdges);
    }

    private void refreshServiceCallEdges(String serviceId, String serviceName) {
        List<String> targetServices = db.sql("""
                SELECT DISTINCT gn_to.service
                FROM graph_edges e
                JOIN graph_nodes gn_from ON e.from_node = gn_from.id
                JOIN graph_nodes gn_to   ON e.to_node   = gn_to.id
                WHERE e.edge_type = 'OUTBOUND_TO'
                  AND gn_from.service = :serviceName
                  AND gn_to.service <> :serviceName
                """)
                .param("serviceName", serviceName)
                .query(String.class)
                .list();

        String serviceNodeId = GraphNodeIds.serviceNode(serviceId);
        List<GraphEdge> calls = new ArrayList<>();
        for (String targetService : targetServices) {
            findServiceNodeIdByName(targetService).ifPresent(targetId ->
                    calls.add(GraphEdge.calls(serviceNodeId, targetId)));
        }
        edgeUpdater.replaceEdges(serviceNodeId, "CALLS", calls);
    }

    private Optional<String> findLibraryTypeNode(String typeFqn) {
        return db.sql("""
                SELECT id FROM graph_nodes
                WHERE symbol_fqn = :fqn AND module_type = 'library'
                LIMIT 1
                """)
                .param("fqn", typeFqn)
                .query(String.class)
                .optional();
    }

    private Optional<String> findEndpointNodeByHttp(String httpMethod, String path) {
        if (httpMethod == null || path == null) return Optional.empty();
        return db.sql("""
                SELECT gn.id
                FROM symbol_facts sf
                JOIN graph_nodes gn ON gn.symbol_fqn = sf.symbol_fqn AND gn.node_type = 'ENDPOINT'
                WHERE sf.symbol_kind = 'ENDPOINT'
                  AND sf.attributes->>'httpMethod' = :method
                  AND sf.attributes->>'path' = :path
                ORDER BY sf.indexed_at DESC
                LIMIT 1
                """)
                .param("method", httpMethod)
                .param("path", path)
                .query(String.class)
                .optional();
    }

    private Optional<String> findServiceNodeIdByName(String serviceName) {
        return db.sql("""
                SELECT gn.id
                FROM graph_nodes gn
                JOIN service_registry sr ON sr.service_id = gn.id
                WHERE sr.service_name = :name AND gn.node_type = 'SERVICE'
                LIMIT 1
                """)
                .param("name", serviceName)
                .query(String.class)
                .optional();
    }

    static String resolveTypeFqn(String typeName, String owningClassFqn) {
        if (typeName == null || typeName.isBlank()) return "";
        String cleaned = typeName.replaceAll("<.*>", "").trim();
        if (cleaned.contains(".")) return cleaned;
        int dot = owningClassFqn.lastIndexOf('.');
        if (dot < 0) return cleaned;
        return owningClassFqn.substring(0, dot + 1) + cleaned;
    }

}

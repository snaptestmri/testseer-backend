package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalEndpointGraphProjector {

    private static final Logger log = LoggerFactory.getLogger(ExternalEndpointGraphProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;

    public ExternalEndpointGraphProjector(GraphNodeRepository nodeRepo, GraphEdgeRepository edgeRepo) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
    }

    @Transactional
    public void project(FactBatch batch) {
        for (FactBatch.ExternalEndpointFact ep : batch.externalEndpointFacts()) {
            String nodeId = GraphNodeIds.externalEndpointNode(
                    batch.orgId(), ep.envLane(), ep.endpointId());
            nodeRepo.upsert(new GraphNode(
                    nodeId,
                    batch.orgId(),
                    batch.repo(),
                    "external",
                    "external",
                    "EXTERNAL_ENDPOINT",
                    ep.endpointId() + "@" + ep.envLane()
            ));

            if (ep.callerClassFqn() != null) {
                String classNodeId = GraphNodeIds.classNode(batch.serviceId(), ep.callerClassFqn());
                nodeRepo.upsert(GraphNode.clazz(
                        classNodeId, batch.orgId(), batch.repo(), batch.serviceId(), ep.callerClassFqn()));
                edgeRepo.insert(GraphEdge.callsExternal(classNodeId, nodeId, ep.confidence()));
            } else if (ep.clientClassFqn() != null) {
                String clientFqn = resolveClientFqn(batch, ep.clientClassFqn());
                if (clientFqn != null) {
                    String classNodeId = GraphNodeIds.classNode(batch.serviceId(), clientFqn);
                    nodeRepo.upsert(GraphNode.clazz(
                            classNodeId, batch.orgId(), batch.repo(), batch.serviceId(), clientFqn));
                    edgeRepo.insert(GraphEdge.callsExternal(classNodeId, nodeId, ep.confidence()));
                }
            }
        }

        for (FactBatch.ExternalCallSiteFact site : batch.externalCallSiteFacts()) {
            if (site.endpointId() == null || site.sourceSymbol() == null) continue;
            String envLane = "unknown";
            String nodeId = GraphNodeIds.externalEndpointNode(
                    batch.orgId(), envLane, site.endpointId());
            nodeRepo.upsert(new GraphNode(
                    nodeId,
                    batch.orgId(),
                    batch.repo(),
                    "external",
                    "external",
                    "EXTERNAL_ENDPOINT",
                    site.endpointId() + "@" + envLane
            ));
            String classFqn = site.sourceSymbol().contains("#")
                    ? site.sourceSymbol().substring(0, site.sourceSymbol().indexOf('#'))
                    : site.sourceSymbol();
            String classNodeId = GraphNodeIds.classNode(batch.serviceId(), classFqn);
            nodeRepo.upsert(GraphNode.clazz(
                    classNodeId, batch.orgId(), batch.repo(), batch.serviceId(), classFqn));
            edgeRepo.insert(GraphEdge.callsExternal(classNodeId, nodeId, site.confidence()));
        }

        log.debug("External endpoint graph projection complete for {} ({} endpoints)",
                batch.serviceId(), batch.externalEndpointFacts().size());
    }

    private static String resolveClientFqn(FactBatch batch, String simpleOrFqn) {
        if (simpleOrFqn.contains(".")) return simpleOrFqn;
        String suffix = "." + simpleOrFqn;
        return batch.symbolFacts().stream()
                .map(FactBatch.SymbolFact::symbolFqn)
                .filter(fqn -> fqn.endsWith(suffix))
                .findFirst()
                .orElse(null);
    }
}

package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessagingGraphProjector {

    private static final Logger log = LoggerFactory.getLogger(MessagingGraphProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;

    public MessagingGraphProjector(GraphNodeRepository nodeRepo, GraphEdgeRepository edgeRepo) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
    }

    @Transactional
    public void project(FactBatch batch) {
        for (FactBatch.PubSubResourceFact p : batch.pubsubResourceFacts()) {
            String nodeType = "TOPIC".equals(p.resourceKind()) ? "TOPIC" : "SUBSCRIPTION";
            String nodeId = batch.orgId() + ":" + nodeType.toLowerCase() + ":" +
                    p.envLane() + ":" + p.shortId();
            nodeRepo.upsert(new GraphNode(
                    nodeId, batch.orgId(), batch.repo(), batch.serviceId(),
                    "service", nodeType, p.shortId() + "@" + p.envLane()
            ));

            if (p.linkedClassFqn() != null) {
                String classNodeId = classNodeId(batch, p.linkedClassFqn());
                ensureClassNode(batch, p.linkedClassFqn(), classNodeId);
                String edgeType = "PUBLISH".equals(p.role()) ? "PUBLISHES_TO" : "SUBSCRIBES_TO";
                edgeRepo.insert(new GraphEdge(
                        classNodeId, nodeId, edgeType, p.confidence(), "messaging-indexer"
                ));
            }
        }

        for (FactBatch.FlowGateFact g : batch.flowGateFacts()) {
            if (g.guardedSymbolFqn() == null) continue;
            String gateId = batch.orgId() + ":gate:" + g.gateKey() + ":"
                    + GraphNodeIds.compactSuffix(g.guardedSymbolFqn(), 200);
            nodeRepo.upsert(new GraphNode(
                    gateId, batch.orgId(), batch.repo(), batch.serviceId(),
                    "service", "GATE", g.gateKey()
            ));
            String classNodeId = classNodeId(batch, g.guardedSymbolFqn());
            ensureClassNode(batch, g.guardedSymbolFqn(), classNodeId);
            edgeRepo.insert(new GraphEdge(
                    classNodeId, gateId, "GUARDED_BY", g.confidence(), "gate-indexer"
            ));
        }

        log.debug("Messaging graph projection complete for {}", batch.serviceId());
    }

    private static String classNodeId(FactBatch batch, String classFqn) {
        return GraphNodeIds.classNode(batch.serviceId(), classFqn);
    }

    private void ensureClassNode(FactBatch batch, String classFqn, String classNodeId) {
        nodeRepo.upsert(GraphNode.clazz(
                classNodeId, batch.orgId(), batch.repo(), batch.serviceId(), classFqn));
    }
}

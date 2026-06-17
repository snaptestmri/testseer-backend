package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntryTriggerGraphProjector {

    private static final Logger log = LoggerFactory.getLogger(EntryTriggerGraphProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;

    public EntryTriggerGraphProjector(GraphNodeRepository nodeRepo, GraphEdgeRepository edgeRepo) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
    }

    @Transactional
    public void project(FactBatch batch) {
        for (FactBatch.EntryTriggerFact trigger : batch.entryTriggerFacts()) {
            String triggerNodeId = GraphNodeIds.entryTriggerNode(
                    batch.orgId(), batch.serviceId(), trigger.envLane(), trigger.triggerId());

            nodeRepo.upsert(new GraphNode(
                    triggerNodeId,
                    batch.orgId(),
                    batch.repo(),
                    batch.serviceId(),
                    batch.serviceId(),
                    "ENTRY_TRIGGER",
                    trigger.triggerId() + "@" + trigger.envLane()
            ));

            if (trigger.linkedHandlerFqn() == null) continue;

            String handlerNodeId = GraphNodeIds.classNode(batch.serviceId(), trigger.linkedHandlerFqn());
            nodeRepo.upsert(GraphNode.clazz(
                    handlerNodeId, batch.orgId(), batch.repo(), batch.serviceId(), trigger.linkedHandlerFqn()));
            edgeRepo.insert(GraphEdge.triggeredBy(triggerNodeId, handlerNodeId, trigger.confidence()));

            if (trigger.pathPattern() != null && trigger.httpMethod() != null) {
                String endpointFqn = trigger.linkedHandlerFqn() + "#" + trigger.linkedMethod();
                String endpointNodeId = GraphNodeIds.endpointNode(batch.serviceId(), endpointFqn);
                nodeRepo.upsert(new GraphNode(
                        endpointNodeId,
                        batch.orgId(),
                        batch.repo(),
                        batch.serviceId(),
                        batch.serviceId(),
                        "ENDPOINT",
                        endpointFqn
                ));
                edgeRepo.insert(new GraphEdge(
                        triggerNodeId, endpointNodeId, "EXPOSES", trigger.confidence(), "entry-trigger-indexer"));
            }
        }

        log.debug("Entry trigger graph projection complete for {} ({} triggers)",
                batch.serviceId(), batch.entryTriggerFacts().size());
    }
}

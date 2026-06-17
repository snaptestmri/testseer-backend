package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.triggers.ComponentScanParser;
import io.testseer.backend.ingestion.triggers.SpringBootLauncherDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects {@code WIRES} edges from Spring Boot main → {@code @ComponentScan} targets (BL-054). */
@Service
public class ComponentScanWiringProjector {

    private static final Logger log = LoggerFactory.getLogger(ComponentScanWiringProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;

    public ComponentScanWiringProjector(GraphNodeRepository nodeRepo, GraphEdgeRepository edgeRepo) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
    }

    @Transactional
    public void project(FactBatch batch, List<ParsedModel> models, Map<String, String> contentByPath) {
        if (models == null || models.isEmpty()) {
            return;
        }
        int edgeCount = 0;
        for (ParsedModel model : models) {
            if (model.classFqn() == null) {
                continue;
            }
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (!SpringBootLauncherDetector.isLongRunningSpringBootMain(model, content)) {
                continue;
            }
            List<String> targets = ComponentScanParser.resolveScanTargetFqns(content, model.classFqn());
            if (targets.isEmpty()) {
                continue;
            }

            String fromNode = GraphNodeIds.classNode(batch.serviceId(), model.classFqn());
            nodeRepo.upsert(GraphNode.clazz(
                    fromNode, batch.orgId(), batch.repo(), batch.serviceId(), model.classFqn()));

            Set<String> seenTargets = new LinkedHashSet<>();
            for (String targetFqn : targets) {
                if (!seenTargets.add(targetFqn)) {
                    continue;
                }
                String toNode = GraphNodeIds.classNode(batch.serviceId(), targetFqn);
                nodeRepo.upsert(GraphNode.clazz(
                        toNode, batch.orgId(), batch.repo(), batch.serviceId(), targetFqn));
                edgeRepo.insert(GraphEdge.wires(fromNode, toNode));
                edgeCount++;
            }
        }
        log.debug("Component-scan wiring projection for {}: {} WIRES edges", batch.serviceId(), edgeCount);
    }
}

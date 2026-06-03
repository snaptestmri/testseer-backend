package io.testseer.backend.graph;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class IncrementalEdgeUpdater {

    private final GraphEdgeRepository edgeRepo;

    public IncrementalEdgeUpdater(GraphEdgeRepository edgeRepo) {
        this.edgeRepo = edgeRepo;
    }

    @Transactional
    public void replaceEdges(String fromNodeId, String edgeType, List<GraphEdge> newEdges) {
        edgeRepo.deleteFromNode(fromNodeId, edgeType);
        newEdges.forEach(edgeRepo::insert);
    }
}

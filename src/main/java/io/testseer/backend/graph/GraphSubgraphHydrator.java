package io.testseer.backend.graph;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class GraphSubgraphHydrator {

    private static final List<String> DEFAULT_EDGE_TYPES = List.of(
            "CALLS", "DEPENDS_ON", "INVOKES", "ROUTES_TO", "OUTBOUND_TO",
            "USES_TYPE", "PUBLISHES_TO", "SUBSCRIBES_TO", "GUARDED_BY", "TRIGGERED_BY");

    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;

    public GraphSubgraphHydrator(GraphNodeRepository nodeRepo, GraphEdgeRepository edgeRepo) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
    }

    public ReachabilityResult hydrate(String anchorNodeId, List<String> reachableIds) {
        return hydrate(anchorNodeId, reachableIds, DEFAULT_EDGE_TYPES);
    }

    public ReachabilityResult hydrate(
            String anchorNodeId, List<String> reachableIds, List<String> edgeTypes) {
        List<String> reachable = reachableIds == null ? List.of() : List.copyOf(reachableIds);
        Set<String> loadSet = new LinkedHashSet<>(reachable);
        if (anchorNodeId != null && !anchorNodeId.isBlank()) {
            loadSet.add(anchorNodeId);
        }
        if (loadSet.isEmpty()) {
            return new ReachabilityResult(List.of(), List.of(), List.of());
        }
        List<String> loadIds = List.copyOf(loadSet);
        List<GraphNode> nodes = nodeRepo.findByIds(loadIds);
        List<GraphEdge> edges = edgeRepo.findEdgesBetween(loadIds, edgeTypes);
        return new ReachabilityResult(reachable, nodes, edges);
    }
}

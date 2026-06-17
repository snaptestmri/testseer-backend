package io.testseer.backend.graph;

import java.util.List;

public record ReachabilityResult(
        List<String> nodeIds,
        List<GraphNode> nodes,
        List<GraphEdge> edges
) {
    public ReachabilityResult(List<String> nodeIds, List<GraphNode> nodes) {
        this(nodeIds, nodes, List.of());
    }

    public int size() { return nodeIds.size(); }
    public boolean isEmpty() { return nodeIds.isEmpty(); }
}

package io.testseer.backend.graph;

import java.util.List;

public record ReachabilityResult(
        List<String> nodeIds,
        List<GraphNode> nodes
) {
    public int size() { return nodeIds.size(); }
    public boolean isEmpty() { return nodeIds.isEmpty(); }
}

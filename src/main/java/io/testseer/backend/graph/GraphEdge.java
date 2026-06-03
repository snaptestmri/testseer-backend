package io.testseer.backend.graph;

public record GraphEdge(
        String fromNode,
        String toNode,
        String edgeType,    // "CALLS" | "DEPENDS_ON" | "OUTBOUND_TO" | "USES_TYPE"
        double confidence,
        String evidenceSource
) {
    public static GraphEdge calls(String from, String to) {
        return new GraphEdge(from, to, "CALLS", 1.0, "javaparser");
    }

    public static GraphEdge dependsOn(String from, String to) {
        return new GraphEdge(from, to, "DEPENDS_ON", 1.0, "javaparser");
    }

    public static GraphEdge outboundTo(String from, String to) {
        return new GraphEdge(from, to, "OUTBOUND_TO", 0.9, "javaparser");
    }

    public static GraphEdge usesType(String from, String to) {
        return new GraphEdge(from, to, "USES_TYPE", 1.0, "javaparser");
    }
}

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

    public static GraphEdge invokes(String from, String to) {
        return new GraphEdge(from, to, "INVOKES", 0.9, "javaparser");
    }

    public static GraphEdge routesTo(String from, String to) {
        return new GraphEdge(from, to, "ROUTES_TO", 0.95, "factory-routing");
    }

    public static GraphEdge callsExternal(String from, String to, double confidence) {
        return new GraphEdge(from, to, "CALLS_EXTERNAL", confidence, "external-endpoint-indexer");
    }

    public static GraphEdge triggeredBy(String triggerNode, String handlerNode, double confidence) {
        return new GraphEdge(triggerNode, handlerNode, "TRIGGERED_BY", confidence, "entry-trigger-indexer");
    }

    public static GraphEdge wires(String from, String to) {
        return new GraphEdge(from, to, "WIRES", 0.88, "component-scan");
    }

    /** TRG-15 / GRP-19: interface API → {@code @RestController} implementation. */
    public static GraphEdge implementsEdge(String interfaceNode, String implNode) {
        return new GraphEdge(interfaceNode, implNode, "IMPLEMENTS", 0.95, "rest-impl-linker");
    }
}

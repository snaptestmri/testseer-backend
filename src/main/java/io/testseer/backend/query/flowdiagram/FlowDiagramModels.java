package io.testseer.backend.query.flowdiagram;

import java.util.List;

public final class FlowDiagramModels {

    private FlowDiagramModels() {}

    public record FlowDiagramResult(
            String serviceId,
            FlowDiagramAnchor anchor,
            List<FlowDiagramNode> nodes,
            List<FlowDiagramEdge> edges,
            List<FlowDiagramGap> gaps,
            FlowDiagramStats stats,
            String mermaid
    ) {}

    public record FlowDiagramAnchor(
            String kind,
            String triggerId,
            String linkedHandlerFqn,
            String symbolFqn,
            String nodeId,
            boolean autoSelected
    ) {}

    public record FlowDiagramNode(
            String nodeId,
            String kind,
            String symbolFqn,
            String simpleName,
            String moduleScope,
            String role,
            List<String> annotations,
            List<FlowDiagramGate> gates,
            String shortId,
            String transport
    ) {}

    public record FlowDiagramGate(
            String gateKey,
            String effectWhenFail
    ) {}

    public record FlowDiagramEdge(
            String from,
            String to,
            String edgeType,
            String label
    ) {}

    public record FlowDiagramGap(
            String gapType,
            String shortId,
            String description
    ) {}

    public record FlowDiagramStats(
            int nodeCount,
            int edgeCount,
            int consumerClassCount
    ) {}

    public record ComposeRequest(
            String orgId,
            String serviceId,
            String anchor,
            String packagePrefix,
            int depth,
            boolean includeMessaging,
            boolean includeExternalDomain,
            boolean includeGates,
            String format
    ) {}
}

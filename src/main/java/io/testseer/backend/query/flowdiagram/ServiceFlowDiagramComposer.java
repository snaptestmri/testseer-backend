package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.config.DomainActorRulePackLoader;
import io.testseer.backend.graph.GraphEdge;
import io.testseer.backend.graph.GraphNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ServiceFlowDiagramComposer {

    private final FlowDiagramAnchorResolver anchorResolver;
    private final FlowDiagramGraphExpander expander;
    private final DomainActorRoleEnricher roleEnricher;
    private final NodeLabelEnricher labelEnricher;
    private final FlowDiagramMermaidRenderer mermaidRenderer;
    private final DomainActorRulePackLoader domainActorRulePackLoader;

    public ServiceFlowDiagramComposer(
            FlowDiagramAnchorResolver anchorResolver,
            FlowDiagramGraphExpander expander,
            DomainActorRoleEnricher roleEnricher,
            NodeLabelEnricher labelEnricher,
            FlowDiagramMermaidRenderer mermaidRenderer,
            DomainActorRulePackLoader domainActorRulePackLoader) {
        this.anchorResolver = anchorResolver;
        this.expander = expander;
        this.roleEnricher = roleEnricher;
        this.labelEnricher = labelEnricher;
        this.mermaidRenderer = mermaidRenderer;
        this.domainActorRulePackLoader = domainActorRulePackLoader;
    }

    public FlowDiagramModels.FlowDiagramResult compose(FlowDiagramModels.ComposeRequest req) {
        FlowDiagramAnchorResolver.ResolvedAnchor resolved =
                anchorResolver.resolve(req.orgId(), req.serviceId(), req.anchor(), req.packagePrefix());

        FlowDiagramGraphExpander.ExpansionResult expansion = expander.expand(
                req.orgId(),
                req.serviceId(),
                resolved.startNodeIds(),
                req.depth(),
                req.packagePrefix(),
                req.includeExternalDomain());

        Map<String, GraphNode> nodes = new LinkedHashMap<>(expansion.nodes());
        List<GraphEdge> edges = new ArrayList<>(expansion.edges());

        expander.addIngress(req.orgId(), req.serviceId(), resolved.trigger(), nodes, edges);

        if (req.includeMessaging()) {
            expander.addMessagingEgress(
                    req.orgId(), req.serviceId(), nodes, edges,
                    req.packagePrefix(), req.includeExternalDomain());
        }

        Set<String> classFqns = new LinkedHashSet<>();
        for (GraphNode node : nodes.values()) {
            if ("CLASS".equals(node.nodeType()) && node.symbolFqn() != null) {
                classFqns.add(node.symbolFqn());
            }
        }

        Map<String, List<String>> annotations = labelEnricher.loadAnnotations(req.serviceId(), classFqns);
        Map<String, List<FlowDiagramModels.FlowDiagramGate>> gates = req.includeGates()
                ? labelEnricher.loadGates(req.serviceId(), classFqns)
                : Map.of();

        List<FlowDiagramModels.FlowDiagramNode> diagramNodes = new ArrayList<>();
        for (GraphNode node : nodes.values()) {
            String classFqn = classFqn(node.symbolFqn());
            String role = roleEnricher.resolveRole(node, req.packagePrefix());
            String moduleScope = roleEnricher.resolveModuleScope(node, req.packagePrefix());
            String simpleName = simpleName(node.symbolFqn(), node.nodeType());
            String shortId = "TOPIC".equals(node.nodeType()) || "SUBSCRIPTION".equals(node.nodeType())
                    ? topicShortId(node.symbolFqn())
                    : null;
            diagramNodes.add(new FlowDiagramModels.FlowDiagramNode(
                    node.id(),
                    node.nodeType(),
                    node.symbolFqn(),
                    simpleName,
                    moduleScope,
                    role,
                    annotations.getOrDefault(classFqn, List.of()),
                    gates.getOrDefault(classFqn, List.of()),
                    shortId,
                    transportFor(node)));
        }

        List<FlowDiagramModels.FlowDiagramEdge> diagramEdges = edges.stream()
                .map(e -> new FlowDiagramModels.FlowDiagramEdge(
                        e.fromNode(),
                        e.toNode(),
                        e.edgeType(),
                        edgeLabel(e)))
                .toList();

        List<FlowDiagramModels.FlowDiagramGap> gaps = buildGaps(req, nodes);

        int consumerClassCount = (int) diagramNodes.stream()
                .filter(n -> "CLASS".equals(n.kind()) && "consumer".equals(n.moduleScope()))
                .count();

        FlowDiagramModels.FlowDiagramStats stats = new FlowDiagramModels.FlowDiagramStats(
                diagramNodes.size(), diagramEdges.size(), consumerClassCount);

        String mermaid = "mermaid".equalsIgnoreCase(req.format())
                ? mermaidRenderer.render(diagramNodes, diagramEdges)
                : null;

        return new FlowDiagramModels.FlowDiagramResult(
                req.serviceId(),
                resolved.anchor(),
                diagramNodes,
                diagramEdges,
                gaps,
                stats,
                mermaid);
    }

    private List<FlowDiagramModels.FlowDiagramGap> buildGaps(
            FlowDiagramModels.ComposeRequest req, Map<String, GraphNode> nodes) {
        if (!req.includeMessaging()) {
            return List.of();
        }
        Set<String> linked = expander.linkedPublishTopics(req.serviceId());
        Set<String> presentTopics = new LinkedHashSet<>();
        for (GraphNode node : nodes.values()) {
            if ("TOPIC".equals(node.nodeType()) && node.symbolFqn() != null) {
                presentTopics.add(topicShortId(node.symbolFqn()));
            }
        }
        presentTopics.addAll(linked);

        List<FlowDiagramModels.FlowDiagramGap> gaps = new ArrayList<>();
        for (String topic : domainActorRulePackLoader.getRulePack().expectedEgressTopics()) {
            if (!presentTopics.contains(topic)) {
                gaps.add(new FlowDiagramModels.FlowDiagramGap(
                        "NO_MESSAGING_HOP",
                        topic,
                        "Producer not linked in event-flow (BL-050)"));
            }
        }
        return gaps;
    }

    private static String edgeLabel(GraphEdge edge) {
        if ("ROUTES_TO".equals(edge.edgeType()) && edge.evidenceSource() != null) {
            return edge.evidenceSource();
        }
        if ("SUBSCRIBES_TO".equals(edge.edgeType())) {
            return "ingress";
        }
        return null;
    }

    private static String classFqn(String symbolFqn) {
        if (symbolFqn == null) {
            return null;
        }
        int hash = symbolFqn.indexOf('#');
        int at = symbolFqn.indexOf('@');
        if (hash > 0) {
            return symbolFqn.substring(0, hash);
        }
        if (at > 0) {
            return symbolFqn.substring(0, at);
        }
        return symbolFqn;
    }

    private static String simpleName(String symbolFqn, String nodeType) {
        if (symbolFqn == null) {
            return nodeType;
        }
        if (symbolFqn.contains("#")) {
            int hash = symbolFqn.indexOf('#');
            String classPart = symbolFqn.substring(0, hash);
            String method = symbolFqn.substring(hash + 1);
            return simpleNameFromFqn(classPart) + "#" + method;
        }
        if (symbolFqn.contains("@")) {
            return symbolFqn.substring(0, symbolFqn.indexOf('@'));
        }
        return simpleNameFromFqn(symbolFqn);
    }

    private static String simpleNameFromFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String topicShortId(String symbolFqn) {
        if (symbolFqn == null) {
            return null;
        }
        int at = symbolFqn.indexOf('@');
        return at > 0 ? symbolFqn.substring(0, at) : symbolFqn;
    }

    private static String transportFor(GraphNode node) {
        if ("TOPIC".equals(node.nodeType())) {
            return "KAFKA";
        }
        return null;
    }
}

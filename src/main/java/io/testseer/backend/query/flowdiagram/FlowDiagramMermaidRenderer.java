package io.testseer.backend.query.flowdiagram;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FlowDiagramMermaidRenderer {

    public String render(
            List<FlowDiagramModels.FlowDiagramNode> nodes,
            List<FlowDiagramModels.FlowDiagramEdge> edges) {
        StringBuilder sb = new StringBuilder("flowchart TB\n");
        Set<String> nodeIds = nodes.stream().map(FlowDiagramModels.FlowDiagramNode::nodeId).collect(Collectors.toSet());

        appendSubgraph(sb, "entry", nodes, n ->
                "TOPIC".equals(n.kind()) || "SUBSCRIPTION".equals(n.kind())
                        || "KAFKA_IN".equals(n.role()));
        appendSubgraph(sb, "core", nodes, n ->
                "consumer".equals(n.moduleScope())
                        && n.simpleName() != null
                        && (n.simpleName().contains("Evaluation")
                            || n.simpleName().contains("Factory")
                            || n.simpleName().contains("Consumer")));
        appendSubgraph(sb, "processors", nodes, n -> "PROCESSOR".equals(n.role()));
        appendSubgraph(sb, "exits", nodes, n ->
                "TOPIC".equals(n.kind()) && !"KAFKA_IN".equals(n.role())
                        || "messaging".equals(n.moduleScope()));

        for (FlowDiagramModels.FlowDiagramEdge edge : edges) {
            if (!nodeIds.contains(edge.from()) || !nodeIds.contains(edge.to())) {
                continue;
            }
            String from = mermaidId(edge.from());
            String to = mermaidId(edge.to());
            String label = edge.label() != null ? "|" + sanitize(edge.label()) + "|" : "";
            boolean dashed = edge.label() != null && edge.label().contains("gate");
            String arrow = dashed ? "-.->" : "-->";
            sb.append("  ").append(from).append(" ").append(arrow).append(label).append(" ").append(to).append("\n");
        }
        return sb.toString();
    }

    private void appendSubgraph(
            StringBuilder sb,
            String name,
            List<FlowDiagramModels.FlowDiagramNode> nodes,
            java.util.function.Predicate<FlowDiagramModels.FlowDiagramNode> filter) {
        List<FlowDiagramModels.FlowDiagramNode> subset = nodes.stream().filter(filter).toList();
        if (subset.isEmpty()) {
            return;
        }
        sb.append("  subgraph ").append(name).append("\n");
        for (FlowDiagramModels.FlowDiagramNode node : subset) {
            sb.append("    ").append(mermaidId(node.nodeId()))
                    .append("[").append(sanitize(nodeLabel(node))).append("]\n");
        }
        sb.append("  end\n");
    }

    private static String nodeLabel(FlowDiagramModels.FlowDiagramNode node) {
        String base = node.simpleName() != null ? node.simpleName() : node.nodeId();
        if (node.role() != null && !node.role().isBlank()) {
            return base + " (" + node.role() + ")";
        }
        return base;
    }

    static String mermaidId(String nodeId) {
        return "n" + Integer.toHexString(nodeId.hashCode()).replace('-', 'x');
    }

    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "'").replace("#", "").replace("\n", " ");
    }
}

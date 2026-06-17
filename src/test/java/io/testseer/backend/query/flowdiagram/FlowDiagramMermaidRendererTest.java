package io.testseer.backend.query.flowdiagram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowDiagramMermaidRendererTest {

    private final FlowDiagramMermaidRenderer renderer = new FlowDiagramMermaidRenderer();

    @Test
    void rendersFlowchartWithProcessorFactory() {
        String nodeA = "svc::class::com.example.Consumer";
        String nodeB = "svc::class::com.example.ProcessorFactory";
        List<FlowDiagramModels.FlowDiagramNode> nodes = List.of(
                new FlowDiagramModels.FlowDiagramNode(
                        nodeA, "CLASS", "com.example.Consumer", "Consumer",
                        "consumer", "KAFKA_IN", List.of(), List.of(), null, null),
                new FlowDiagramModels.FlowDiagramNode(
                        nodeB, "CLASS", "com.example.ProcessorFactory", "ProcessorFactory",
                        "consumer", null, List.of(), List.of(), null, null));
        List<FlowDiagramModels.FlowDiagramEdge> edges = List.of(
                new FlowDiagramModels.FlowDiagramEdge(nodeA, nodeB, "INVOKES", null));

        String mermaid = renderer.render(nodes, edges);

        assertThat(mermaid).startsWith("flowchart TB");
        assertThat(mermaid).contains("ProcessorFactory");
        assertThat(mermaid).contains("-->");
    }
}

package io.testseer.backend.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphSubgraphHydratorTest {

    @Mock GraphNodeRepository nodeRepo;
    @Mock GraphEdgeRepository edgeRepo;

    GraphSubgraphHydrator hydrator;

    @BeforeEach
    void setUp() {
        hydrator = new GraphSubgraphHydrator(nodeRepo, edgeRepo);
    }

    @Test
    void hydrate_keepsReachableIdsWithoutAnchor_butLoadsAnchorForEdges() {
        GraphNode anchor = GraphNode.service("svc-orders", "org", "repo", "orders");
        GraphNode callee = GraphNode.service("svc-inventory", "org", "repo", "inventory");
        GraphEdge edge = GraphEdge.calls("svc-orders", "svc-inventory");

        when(nodeRepo.findByIds(anyList())).thenReturn(List.of(anchor, callee));
        when(edgeRepo.findEdgesBetween(anyList(), anyList())).thenReturn(List.of(edge));

        ReachabilityResult result = hydrator.hydrate("svc-orders", List.of("svc-inventory"));

        assertThat(result.nodeIds()).containsExactly("svc-inventory");
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.edges()).singleElement().isEqualTo(edge);
    }

    @Test
    void hydrate_includesAnchorInNodesButNotInNodeIds() {
        GraphNode anchor = GraphNode.clazz(
                "svc::class::com.example.Consumer", "org", "repo", "svc", "com.example.Consumer");

        when(nodeRepo.findByIds(anyList())).thenReturn(List.of(anchor));
        when(edgeRepo.findEdgesBetween(anyList(), anyList())).thenReturn(List.of());

        ReachabilityResult result = hydrator.hydrate(anchor.id(), List.of());

        assertThat(result.nodeIds()).isEmpty();
        assertThat(result.nodes()).extracting(GraphNode::id).containsExactly(anchor.id());
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void hydrate_emptyLoadSet_returnsEmptyTriple() {
        ReachabilityResult result = hydrator.hydrate(null, List.of());

        assertThat(result.nodeIds()).isEmpty();
        assertThat(result.nodes()).isEmpty();
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void hydrate_passesEdgeTypeFilterToRepository() {
        GraphNode a = GraphNode.service("svc-a", "org", "repo", "a");
        GraphNode b = GraphNode.service("svc-b", "org", "repo", "b");
        List<String> edgeTypes = List.of("INVOKES", "ROUTES_TO");

        when(nodeRepo.findByIds(anyList())).thenReturn(List.of(a, b));
        when(edgeRepo.findEdgesBetween(anyList(), org.mockito.ArgumentMatchers.eq(edgeTypes)))
                .thenReturn(List.of(GraphEdge.invokes("svc-a", "svc-b")));

        ReachabilityResult result = hydrator.hydrate("svc-a", List.of("svc-b"), edgeTypes);

        assertThat(result.edges()).hasSize(1);
        assertThat(result.edges().getFirst().edgeType()).isEqualTo("INVOKES");
    }
}

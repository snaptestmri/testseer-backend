package io.testseer.backend.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphNodeIdsTest {

    @Test
    void nodeIds_areDeterministic() {
        assertThat(GraphNodeIds.serviceNode("svc-001")).isEqualTo("svc-001");
        assertThat(GraphNodeIds.classNode("svc-001", "io.orders.OrderController"))
                .isEqualTo("svc-001::class::io.orders.OrderController");
        assertThat(GraphNodeIds.endpointNode("svc-001", "io.orders.OrderController#getOrder"))
                .isEqualTo("svc-001::endpoint::io.orders.OrderController#getOrder");
    }

    @Test
    void resolveTypeFqn_resolvesSimpleNames() {
        assertThat(GraphFactProjector.resolveTypeFqn(
                "OrderService", "io.orders.OrderController"))
                .isEqualTo("io.orders.OrderService");
        assertThat(GraphFactProjector.resolveTypeFqn(
                "io.billing.Client", "io.orders.OrderController"))
                .isEqualTo("io.billing.Client");
    }
}

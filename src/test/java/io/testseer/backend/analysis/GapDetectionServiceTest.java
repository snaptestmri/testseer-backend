package io.testseer.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GapDetectionServiceTest {

    @Test
    void hasTest_matchesFooTest() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderController",
                Set.of("io.orders.OrderControllerTest"))).isTrue();
    }

    @Test
    void hasTest_matchesFooTests() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderService",
                Set.of("io.orders.OrderServiceTests"))).isTrue();
    }

    @Test
    void hasTest_matchesFooIT() {
        assertThat(GapDetectionService.hasTest("io.orders.PaymentClient",
                Set.of("io.orders.PaymentClientIT"))).isTrue();
    }

    @Test
    void hasTest_matchesTestFoo() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderRepository",
                Set.of("io.orders.TestOrderRepository"))).isTrue();
    }

    @Test
    void hasTest_returnsFalse_whenNoMatch() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderController",
                Set.of("io.orders.PaymentServiceTest"))).isFalse();
    }

    @Test
    void hasTest_returnsFalse_whenTestSetEmpty() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderController",
                Set.of())).isFalse();
    }
}

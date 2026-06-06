package io.testseer.backend.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestClassMatcherTest {

    @Test
    void matchesStandardNamingPatterns() {
        assertThat(TestClassMatcher.matches("OrderController", "io.orders.OrderControllerTest"))
                .isTrue();
        assertThat(TestClassMatcher.matches("OrderController", "io.orders.OrderControllerTests"))
                .isTrue();
        assertThat(TestClassMatcher.matches("OrderController", "io.orders.OrderControllerIT"))
                .isTrue();
        assertThat(TestClassMatcher.matches("OrderController", "io.orders.TestOrderController"))
                .isTrue();
        assertThat(TestClassMatcher.matches("OrderController", "io.orders.PaymentClient"))
                .isFalse();
    }
}

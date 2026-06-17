package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackagePrefixFilterTest {

    @Test
    void matchesClassFqn() {
        assertThat(PackagePrefixFilter.matches(
                "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer",
                "com.quotient.platform.transaction.eval")).isTrue();
        assertThat(PackagePrefixFilter.matches(
                "com.quotient.platform.evaluation.common.helper.TransactionHelper",
                "com.quotient.platform.transaction.eval")).isFalse();
    }

    @Test
    void matchesMethodFqn() {
        assertThat(PackagePrefixFilter.matches(
                "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer#processSalesCanonicalEvent",
                "com.quotient.platform.transaction.eval")).isTrue();
    }

    @Test
    void matchesKafkaTriggerWithoutHandlerInPackage() {
        EntryFlowService.EntryTriggerView trigger = new EntryFlowService.EntryTriggerView(
                "kafka:test", "KAFKA_SUBSCRIBE", "INBOUND", "dev",
                "kafka", "INTERNAL", null, "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
                "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer",
                "processSalesCanonicalEvent", null, "yaml", "KAFKA_LISTENER", 0.95);
        assertThat(PackagePrefixFilter.matchesTrigger(trigger, "com.quotient.platform.transaction.eval")).isTrue();
    }
}

package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicAliasIndexTest {

    @Test
    void canonical_resolvesAliasToLogicalTopic() {
        MessagingRulePack pack = new MessagingRulePack(
                List.of(), List.of(), java.util.Map.of(), List.of(), List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(), java.util.Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new MessagingRulePack.KafkaTopicAliasRule(
                        "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
                        List.of("PDN_T.SALES_TRANSACTION_PIPELINE"))),
                MessagingRulePack.CrossRepoTraceRule.empty());

        KafkaTopicAliasIndex index = KafkaTopicAliasIndex.from(pack);

        assertThat(index.canonical("PDN_T.SALES_TRANSACTION_PIPELINE"))
                .isEqualTo("QUOT.SALES.TRANSACTION.PIPELINE.EVENTS");
        assertThat(index.equivalent(
                "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
                "PDN_T.SALES_TRANSACTION_PIPELINE"))
                .isTrue();
    }
}

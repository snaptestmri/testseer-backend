package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyGapServiceTest {

    @Test
    void expectsIndexedScenario_onlyForInferrablePatterns() {
        assertThat(ConsistencyGapService.expectsIndexedScenario(dualWriteRule())).isTrue();
        assertThat(ConsistencyGapService.expectsIndexedScenario(projectionRule())).isFalse();
        assertThat(ConsistencyGapService.expectsIndexedScenario(coTableRule())).isFalse();
        assertThat(ConsistencyGapService.expectsIndexedScenario(null)).isFalse();
    }

    private static MessagingRulePack.ConsistencyRule dualWriteRule() {
        return new MessagingRulePack.ConsistencyRule(
                "DUAL_WRITE_SAME_HANDLER", List.of("HYVEE_ADAPTER"),
                "MARIADB", "PartnerOfferCallRecorder", List.of(), List.of(),
                List.of(), null, null, List.of());
    }

    private static MessagingRulePack.ConsistencyRule projectionRule() {
        return new MessagingRulePack.ConsistencyRule(
                "PROJECTION_SPLIT", List.of("GALO_READ"),
                "MARIADB", "Offer", List.of(), List.of(), List.of(), null, null, List.of());
    }

    private static MessagingRulePack.ConsistencyRule coTableRule() {
        return new MessagingRulePack.ConsistencyRule(
                "CO_TABLE_INVARIANT", List.of("GALO_READ"),
                "MARIADB", "Offer", List.of(), List.of(), List.of(), null, null, List.of());
    }
}

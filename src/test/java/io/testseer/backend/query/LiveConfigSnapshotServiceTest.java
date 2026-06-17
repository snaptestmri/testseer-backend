package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveConfigSnapshotServiceTest {

    @Test
    void evaluate_booleanPassFail() {
        assertThat(LiveConfigSnapshotService.evaluate("true", "true"))
                .isEqualTo(LiveConfigSnapshotService.LiveStatus.PASS);
        assertThat(LiveConfigSnapshotService.evaluate("true", "false"))
                .isEqualTo(LiveConfigSnapshotService.LiveStatus.FAIL);
    }

    @Test
    void overlayGates_disabledReturnsUnknown() {
        MessagingRulePackLoader loader = mock(MessagingRulePackLoader.class);
        when(loader.getRulePack()).thenReturn(MessagingRulePack.empty());

        LiveConfigSnapshotService service = new LiveConfigSnapshotService(
                loader, false, "", "", "", "pdn", 300);

        MessagingFlowService.FlowGateView gate = MessagingFlowService.FlowGateView.fromDb(
                "com.example.Handler", "HYVEE_ADAPTER", "CONFIG", "galo.preLive",
                "true", "SKIP", "Enable preLive", "RULE_PACK", 0.9);

        List<MessagingFlowService.FlowGateView> overlaid =
                service.overlayGates("pdn", List.of(gate), false);

        assertThat(overlaid.get(0).liveStatus()).isEqualTo("UNKNOWN");
        assertThat(service.overlayContext("pdn").liveConfigStatus()).isEqualTo("DISABLED");
    }
}

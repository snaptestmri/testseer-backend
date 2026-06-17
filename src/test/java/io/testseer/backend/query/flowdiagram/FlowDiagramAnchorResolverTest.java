package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.graph.RestHandlerGraphResolver;
import io.testseer.backend.query.EntryFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowDiagramAnchorResolverTest {

    @Mock EntryFlowService entryFlowService;
    @Mock RestHandlerGraphResolver restHandlerGraphResolver;
    @InjectMocks FlowDiagramAnchorResolver resolver;

    @Test
    void resolve_autoSelectsRestInboundWhenAnchorOmitted() {
        EntryFlowService.EntryTriggerView trigger = new EntryFlowService.EntryTriggerView(
                "post:/shopping/history",
                "REST_INBOUND",
                "INBOUND",
                "unknown",
                "EXTERNAL",
                "EXTERNAL",
                "POST",
                "/shopping/history",
                "com.quotient.platform.userprofile.api.UserHistoryApiController",
                "getUserProfileShoppingHistory",
                null,
                null,
                "JAVA_PARSER",
                0.95,
                null);

        when(entryFlowService.queryTriggers(
                eq("svc-1"), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq("com.quotient.platform.userprofile"), eq(false)))
                .thenReturn(List.of(trigger));
        when(entryFlowService.queryTriggers(
                eq("svc-1"), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(List.of(trigger));

        FlowDiagramAnchorResolver.ResolvedAnchor resolved = resolver.resolve(
                "quotient", "svc-1", null, "com.quotient.platform.userprofile");

        assertThat(resolved.anchor().autoSelected()).isTrue();
        assertThat(resolved.anchor().triggerId()).isEqualTo("post:/shopping/history");
        assertThat(resolved.startNodeIds()).isNotEmpty();
    }
}

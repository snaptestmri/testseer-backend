package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.query.EntryFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowDiagramAnchorResolverTest {

    private static final String SVC = "svc-eval";
    private static final String CONSUMER =
            "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer";

    @Mock EntryFlowService entryFlowService;
    @InjectMocks FlowDiagramAnchorResolver resolver;

    @Test
    void resolvesHandlerFqnWithDotNotation() {
        var resolved = resolver.resolve(
                "quotient", SVC,
                "handlerFqn:" + CONSUMER + ".processSalesCanonicalEvent");

        assertThat(resolved.anchor().kind()).isEqualTo("HANDLER");
        assertThat(resolved.startNodeIds()).hasSize(2);
        assertThat(resolved.startNodeIds().get(0)).contains("processSalesCanonicalEvent");
    }

    @Test
    void resolvesSymbolFqn() {
        var resolved = resolver.resolve(
                "quotient", SVC, "symbolFqn:" + CONSUMER);

        assertThat(resolved.anchor().kind()).isEqualTo("SYMBOL");
        assertThat(resolved.startNodeIds().get(0)).contains(CONSUMER);
    }

    @Test
    void resolvesTriggerId() {
        when(entryFlowService.queryTriggers(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new EntryFlowService.EntryTriggerView(
                        "kafka:pipeline", "KAFKA_SUBSCRIBE", "INBOUND", "dev",
                        "kafka", "INTERNAL", null, "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
                        CONSUMER, "processSalesCanonicalEvent",
                        null, "yaml", "KAFKA_LISTENER", 0.95)));

        var resolved = resolver.resolve("quotient", SVC, "triggerId:kafka:pipeline");

        assertThat(resolved.anchor().kind()).isEqualTo("TRIGGER");
        assertThat(resolved.trigger().linkedHandlerFqn()).isEqualTo(CONSUMER);
    }
}

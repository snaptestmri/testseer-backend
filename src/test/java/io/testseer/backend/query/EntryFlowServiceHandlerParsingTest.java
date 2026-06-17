package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryFlowServiceHandlerParsingTest {

    @Test
    void parseHandlerFqn_splitsClassAndMethod() {
        EntryFlowService.ParsedHandler parsed =
                EntryFlowService.parseHandlerFqn("com.example.Foo#onMessage");
        assertThat(parsed.classFqn()).isEqualTo("com.example.Foo");
        assertThat(parsed.method()).isEqualTo("onMessage");
        assertThat(parsed.simpleName()).isEqualTo("Foo");
    }

    @Test
    void parseHandlerFqn_classOnly() {
        EntryFlowService.ParsedHandler parsed =
                EntryFlowService.parseHandlerFqn("com.example.RiqOfferEventConsumer");
        assertThat(parsed.classFqn()).isEqualTo("com.example.RiqOfferEventConsumer");
        assertThat(parsed.method()).isNull();
        assertThat(parsed.simpleName()).isEqualTo("RiqOfferEventConsumer");
    }

    @Test
    void parseHandlerFqn_dotNotation_splitsClassAndMethod() {
        EntryFlowService.ParsedHandler parsed = EntryFlowService.parseHandlerFqn(
                "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer.processSalesCanonicalEvent");
        assertThat(parsed.classFqn()).isEqualTo(
                "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer");
        assertThat(parsed.method()).isEqualTo("processSalesCanonicalEvent");
    }

    @Test
    void parseHandlerFqn_classOnly_doesNotSplitInnerClassLikeSegments() {
        EntryFlowService.ParsedHandler parsed =
                EntryFlowService.parseHandlerFqn("com.example.Outer.Inner");
        assertThat(parsed.classFqn()).isEqualTo("com.example.Outer.Inner");
        assertThat(parsed.method()).isNull();
    }
}

package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PubSubSubscribeTriggerExtractorTest {

    private final PubSubSubscribeTriggerExtractor extractor = new PubSubSubscribeTriggerExtractor();

    @Test
    void extract_skipsKafkaSubscribeFacts() {
        String handler = "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer";
        var kafkaFact = new FactBatch.PubSubResourceFact(
                "TOPIC",
                "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
                "dev",
                "dev",
                null,
                null,
                "SUBSCRIBE",
                "kafka.topics.stxn.pipeline.topic-name",
                "application-dev.yaml",
                "transaction-eval-consumer",
                handler,
                "processSalesCanonicalEvent",
                "transaction-eval-consumer",
                "YAML",
                0.85,
                "{\"transport\":\"KAFKA\",\"topicName\":\"QUOT.SALES.TRANSACTION.PIPELINE.EVENTS\"}");

        assertThat(extractor.extract(List.of(kafkaFact), "dev")).isEmpty();
    }

    @Test
    void extract_createsPubSubSubscribeTriggerForGcpSubscription() {
        String handler = "com.example.RiqOfferEventConsumer";
        var pubsubFact = new FactBatch.PubSubResourceFact(
                "SUBSCRIPTION",
                "PDN_S.RIQ_OFFER_EVENT",
                "pdn",
                "pdn",
                null,
                null,
                "SUBSCRIBE",
                "riq-offer-event",
                "application-pdn.yaml",
                "offer-events-consumer",
                handler,
                "onMessage",
                "offer-events-consumer",
                "YAML",
                1.0,
                null);

        assertThat(extractor.extract(List.of(pubsubFact), "pdn")).singleElement().satisfies(t -> {
            assertThat(t.triggerKind()).isEqualTo("PUBSUB_SUBSCRIBE");
            assertThat(t.pathPattern()).isEqualTo("PDN_S.RIQ_OFFER_EVENT");
            assertThat(t.linkedHandlerFqn()).isEqualTo(handler);
            assertThat(t.evidenceSource()).isEqualTo("PUBSUB_LINK");
        });
    }
}

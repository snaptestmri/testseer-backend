package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaListenerTriggerExtractorTest {

    private final KafkaListenerTriggerExtractor extractor = new KafkaListenerTriggerExtractor();

    @Test
    void extract_createsKafkaSubscribeTriggerFromAnnotation() {
        String source = """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                @ConditionalOnProperty("kafka.topics.stxn.pipeline.enabled")
                public class TransactionEvalConsumer {
                    @KafkaListener(topics = "${kafka.topics.stxn.pipeline.topic-name}",
                        groupId = "${kafka.topics.stxn.pipeline.consumer.group-id}")
                    public void processSalesCanonicalEvent() {}
                }
                """;

        String yaml = """
                kafka:
                  topics:
                    stxn:
                      pipeline:
                        topic-name: QUOT.SALES.TRANSACTION.PIPELINE.EVENTS
                        consumer:
                          group-id: transaction-eval-consumer
                """;

        ParsedModel model = ParsedModel.of(
                "TransactionEvalConsumer.java",
                "com.example.TransactionEvalConsumer",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        List<FactBatch.EntryTriggerFact> triggers = extractor.extract(
                List.of(model),
                Map.of(model.filePath(), source),
                List.of(new YamlPubSubExtractor.ConfigFile("application-dev.yaml", yaml)),
                "dev");

        assertThat(triggers).singleElement().satisfies(t -> {
            assertThat(t.triggerKind()).isEqualTo("KAFKA_SUBSCRIBE");
            assertThat(t.pathPattern()).isEqualTo("QUOT.SALES.TRANSACTION.PIPELINE.EVENTS");
            assertThat(t.linkedHandlerFqn()).isEqualTo("com.example.TransactionEvalConsumer");
            assertThat(t.linkedMethod()).isEqualTo("processSalesCanonicalEvent");
        });
    }
}

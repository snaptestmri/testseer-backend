package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlKafkaTopicExtractorTest {

    private final YamlKafkaTopicExtractor extractor = new YamlKafkaTopicExtractor();

    @Test
    void extract_findsSubscribeAndPublishTopicsFromConfigMap() {
        String configMap = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: transaction-eval-consumer
                data:
                  application.yaml: |-
                    kafka:
                      topics:
                        stxn:
                          pipeline:
                            enabled: true
                            topic-name: QUOT.SALES.TRANSACTION.PIPELINE.EVENTS
                            consumer:
                              enabled: true
                              group-id: transaction-eval-consumer
                          processed:
                            enabled: true
                            topic-name: QUOT.SALES.TRANSACTION.PROCESSED.EVENTS
                            producer:
                              enabled: true
                """;

        List<YamlPubSubExtractor.ConfigFile> files = List.of(
                new YamlPubSubExtractor.ConfigFile(
                        "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml",
                        configMap));

        List<FactBatch.PubSubResourceFact> facts = extractor.extract(files);

        assertThat(facts).anyMatch(f ->
                "SUBSCRIBE".equals(f.role())
                        && "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS".equals(f.shortId())
                        && f.attributes() != null
                        && f.attributes().contains("\"transport\":\"KAFKA\""));
        assertThat(facts).anyMatch(f ->
                "PUBLISH".equals(f.role())
                        && "QUOT.SALES.TRANSACTION.PROCESSED.EVENTS".equals(f.shortId()));
    }

    @Test
    void extract_findsTopicsFromApplicationYaml() {
        String yaml = """
                kafka:
                  topics:
                    rebate:
                      redeem:
                        enabled: true
                        topic-name: QUOT.REBATE.REDEEM.EVENTS
                        producer:
                          enabled: true
                """;

        List<FactBatch.PubSubResourceFact> facts = extractor.extract(List.of(
                new YamlPubSubExtractor.ConfigFile(
                        "transaction-eval-consumer/src/main/resources/application-dev.yaml", yaml)));

        assertThat(facts).anyMatch(f ->
                "PUBLISH".equals(f.role()) && "QUOT.REBATE.REDEEM.EVENTS".equals(f.shortId()));
    }
}

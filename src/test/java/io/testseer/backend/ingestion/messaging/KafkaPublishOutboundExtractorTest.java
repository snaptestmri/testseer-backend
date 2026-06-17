package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPublishOutboundExtractorTest {

    private final KafkaPublishOutboundExtractor extractor = new KafkaPublishOutboundExtractor();

    private static final String PRODUCER_FQN =
            "com.quotient.platform.userprofile.producer.UserEmailAcceptanceRedeemEventProducer";

    @Test
    void beanNameToTopicSegment_mapsRebateRedeemProducer() {
        assertThat(KafkaPublishOutboundExtractor.beanNameToTopicSegment("rebateRedeemSyncProducer"))
                .isEqualTo("rebate.redeem");
    }

    @Test
    void extract_emitsKafkaOutboundForSyncProducerSend() {
        ParsedModel model = producerModel();

        List<FactBatch.PubSubResourceFact> kafkaTopics = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC",
                "QUOT.REBATE.REDEEM.EVENTS",
                "dev",
                "dev",
                null,
                null,
                "PUBLISH",
                "kafka.topics.rebate.redeem.topic-name",
                "application-dev.yaml",
                "user-profile-service",
                PRODUCER_FQN,
                "publishEvent",
                null,
                "YAML_KAFKA",
                0.95,
                "{\"transport\":\"KAFKA\"}"));

        List<FactBatch.OutboundCallFact> facts = extractor.extract(List.of(model), kafkaTopics);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.sourceSymbol()).isEqualTo(PRODUCER_FQN + "#publishEvent");
            assertThat(f.httpMethod()).isEqualTo("KAFKA");
            assertThat(f.path()).isEqualTo("QUOT.REBATE.REDEEM.EVENTS");
            assertThat(f.evidenceSource()).isEqualTo("KAFKA_PUBLISH");
        });
    }

    @Test
    void extract_resolvesTopicFromQualifierWhenPubSubUnlinked() {
        ParsedModel model = producerModel();

        List<FactBatch.PubSubResourceFact> kafkaTopics = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC",
                "QUOT.REBATE.REDEEM.EVENTS",
                "dev",
                "dev",
                null,
                null,
                "PUBLISH",
                "kafka.topics.rebate.redeem.topic-name",
                "application-dev.yaml",
                "user-profile-service",
                null,
                null,
                null,
                "YAML_KAFKA",
                0.95,
                "{\"transport\":\"KAFKA\"}"));

        List<FactBatch.OutboundCallFact> facts = extractor.extract(List.of(model), kafkaTopics);

        assertThat(facts).singleElement()
                .extracting(FactBatch.OutboundCallFact::path)
                .isEqualTo("QUOT.REBATE.REDEEM.EVENTS");
    }

    @Test
    void extract_prefersQualifierTopicWhenMultipleTopicsLinkedToProducer() {
        ParsedModel model = producerModel();

        List<FactBatch.PubSubResourceFact> kafkaTopics = List.of(
                new FactBatch.PubSubResourceFact(
                        "TOPIC", "QUOT.RETRY.TOPIC", "dev", "dev", null, null,
                        "PUBLISH", "kafka.topics.retry.topic-name",
                        "application-dev.yaml", "user-profile-service",
                        PRODUCER_FQN, "publishEvent", null, "YAML_KAFKA", 0.95,
                        "{\"transport\":\"KAFKA\"}"),
                new FactBatch.PubSubResourceFact(
                        "TOPIC", "QUOT.REBATE.REDEEM.EVENTS", "dev", "dev", null, null,
                        "PUBLISH", "kafka.topics.rebate.redeem.topic-name",
                        "application-dev.yaml", "user-profile-service",
                        PRODUCER_FQN, "publishEvent", null, "YAML_KAFKA", 0.95,
                        "{\"transport\":\"KAFKA\"}"));

        List<FactBatch.OutboundCallFact> facts = extractor.extract(List.of(model), kafkaTopics);

        assertThat(facts).singleElement()
                .extracting(FactBatch.OutboundCallFact::path)
                .isEqualTo("QUOT.REBATE.REDEEM.EVENTS");
    }

    private static ParsedModel producerModel() {
        return new ParsedModel(
                "UserEmailAcceptanceRedeemEventProducer.java",
                PRODUCER_FQN,
                List.of("Component"),
                List.of(),
                List.of("SyncProducer"),
                List.of(),
                List.of(),
                false,
                null,
                null,
                List.of(),
                List.of(),
                List.of(new ParsedModel.FieldInjectionDef(
                        "rebateRedeemSyncProducer",
                        "SyncProducer<MessageEnvelope.QMsgEvent>",
                        "rebateRedeemSyncProducer",
                        "Qualifier")),
                List.of(new ParsedModel.MethodCallDef(
                        "publishEvent",
                        "SyncProducer",
                        "send",
                        "rebateRedeemSyncProducer")),
                List.of(),
                null,
                List.of());
    }
}

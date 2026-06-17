package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingClassLinkerTest {

    private final MessagingClassLinker linker = new MessagingClassLinker();

    @Test
    void linkPubSub_linksPublisherAcrossModules() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.UMO_EVENT", "pdn", "pdn", null, null,
                "PUBLISH", "pubsub.publisher.topicId.freedomumo",
                "partner-adapter-consumer/src/main/resources/application-pdn.yaml",
                "partner-adapter-consumer", null, null, null,
                "YAML", 1.0, null));

        var java = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "partner-adapter-lib/src/main/java/com/quotient/platform/partneradapter/lib/service/FreedomOfferUpdateEventPublisher.java",
                "class FreedomOfferUpdateEventPublisher { PubSubMsgGateway publishGateway; "
                        + "getTopicId().get(\"freedomumo\"); sendUpdateManageOfferEvent() {} }",
                "com.quotient.platform.partneradapter.lib.service.FreedomOfferUpdateEventPublisher"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, java);

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedClassFqn()).isEqualTo(
                    "com.quotient.platform.partneradapter.lib.service.FreedomOfferUpdateEventPublisher");
            assertThat(f.evidenceSource()).isEqualTo("JAVA_INFERRED");
        });
    }

    @Test
    void linkPubSub_usesRulePackWhenInferenceMisses() {
        var rulePack = new io.testseer.backend.config.MessagingRulePack(
                List.of(), List.of(), java.util.Map.of(), List.of(), List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(), java.util.Map.of(),
                List.of(new io.testseer.backend.config.MessagingRulePack.PubSubClassLinkRule(
                        null, "freedomumo", "PUBLISH",
                        "com.example.CuratedPublisher", "sendUpdateManageOfferEvent")),
                List.of(), List.of(), List.of(), List.of(),
                io.testseer.backend.config.MessagingRulePack.CrossRepoTraceRule.empty());

        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.UMO_EVENT", "pdn", "pdn", null, null,
                "PUBLISH", "pubsub.publisher.topicId.freedomumo",
                "application-pdn.yaml", "partner-adapter-consumer", null, null, null,
                "YAML", 1.0, null));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, List.of(), rulePack);

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedClassFqn()).isEqualTo("com.example.CuratedPublisher");
            assertThat(f.evidenceSource()).isEqualTo("RULE_PACK");
        });
    }

    @Test
    void linkPubSub_linksPublishServiceWithGateway() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.PARTNER_ADAPTER_NOTIFICATION", "pdn", "pdn", null, null,
                "PUBLISH", "pubsub.publisher.topicId.partneradapter",
                "partner-adapter-consumer/src/main/resources/application-pdn.yaml",
                "partner-adapter-consumer", null, null, null,
                "YAML", 1.0, null));

        var java = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "partner-adapter-consumer/src/main/java/com/quotient/platform/partneradapter/service/PartnerAdapterPublishService.java",
                "class PartnerAdapterPublishService { PubSubMsgGateway publishGateway; publishEvent() {} }",
                "com.quotient.platform.partneradapter.service.PartnerAdapterPublishService"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, java);

        assertThat(linked).singleElement()
                .extracting(FactBatch.PubSubResourceFact::linkedClassFqn)
                .isEqualTo("com.quotient.platform.partneradapter.service.PartnerAdapterPublishService");
    }

    @Test
    void linkPubSub_linksPublisherClass() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.OFFER_UPDATE", "pdn", "pdn", null, null,
                "PUBLISH", "pubsub.publisher.topicId.offerUpdate",
                "offer-events/src/main/resources/application-pdn.yaml",
                "offer-events-consumer", null, null, null,
                "YAML", 1.0, null));

        var java = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "offer-events-consumer/src/main/java/com/example/OfferUpdatePublisher.java",
                "class OfferUpdatePublisher { void publishEvent() {} pubsub.publisher.topicId.offerUpdate }",
                "com.example.OfferUpdatePublisher"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, java);

        assertThat(linked).anyMatch(f ->
                "com.example.OfferUpdatePublisher".equals(f.linkedClassFqn())
                        && "publishEvent".equals(f.linkedMethod()));
    }

    @Test
    void linkPubSub_linksConsumerClass() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "SUBSCRIPTION", "PDN_S.PARTNER_ADAPTER_NOTIFICATION", "pdn", "pdn", null, null,
                "SUBSCRIBE", "consumer.partneradapter.subscription-id",
                "partner-adapter-consumer/src/main/resources/application-pdn.yaml",
                "partner-adapter-consumer", null, null, null,
                "YAML", 1.0, null));

        var java = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "partner-adapter-consumer/src/main/java/com/quotient/platform/partneradapter/consumer/PartnerAdapterConsumer.java",
                "class PartnerAdapterConsumer {}",
                "com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, java);

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedClassFqn())
                    .isEqualTo("com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer");
            assertThat(f.linkedMethod()).isEqualTo("onMessage");
            assertThat(f.evidenceSource()).isEqualTo("JAVA_INFERRED");
        });
    }

    @Test
    void linkPubSub_linksConsumerClass_notSpringBootApplication() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "SUBSCRIPTION", "PDN_S.PARTNER_ADAPTER_NOTIFICATION", "pdn", "pdn", null, null,
                "SUBSCRIBE", "consumer.partneradapter.subscription-id",
                "partner-adapter-consumer/src/main/resources/application-pdn.yaml",
                "partner-adapter-consumer", null, null, null,
                "YAML", 1.0, null));

        var java = List.of(
                new ProtoSchemaExtractor.JavaSourceFile(
                        "partner-adapter-consumer/src/main/java/com/quotient/platform/partneradapter/PartnerAdapterConsumerApplication.java",
                        "class PartnerAdapterConsumerApplication {}",
                        "com.quotient.platform.partneradapter.PartnerAdapterConsumerApplication"),
                new ProtoSchemaExtractor.JavaSourceFile(
                        "partner-adapter-consumer/src/main/java/com/quotient/platform/partneradapter/consumer/PartnerAdapterConsumer.java",
                        "class PartnerAdapterConsumer {}",
                        "com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, java);

        assertThat(linked).singleElement()
                .extracting(FactBatch.PubSubResourceFact::linkedClassFqn)
                .isEqualTo("com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer");
    }

    @Test
    void linkPubSub_usesKafkaRulePackForTransactionEvalEgress() {
        var rulePack = new io.testseer.backend.config.MessagingRulePack(
                List.of(), List.of(), java.util.Map.of(), List.of(), List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(), java.util.Map.of(),
                List.of(),
                List.of(
                        new io.testseer.backend.config.MessagingRulePack.KafkaClassLinkRule(
                                "transaction-eval-consumer",
                                "QUOT.FRAUD.TRANSACTION.PATTERN.EVENTS",
                                "PUBLISH",
                                "com.quotient.platform.transaction.eval.producer.PatternCheckEventProducer",
                                "postPatternCheckEvent")),
                List.of(), List.of(), List.of(),
                io.testseer.backend.config.MessagingRulePack.CrossRepoTraceRule.empty());

        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "QUOT.FRAUD.TRANSACTION.PATTERN.EVENTS", "dev", "dev", null, null,
                "PUBLISH", "kafka.topics.fraud.transaction.pattern.topic-name",
                "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml",
                "transaction-eval-consumer", null, null, null,
                "YAML_KAFKA", 0.95, "{\"transport\":\"KAFKA\"}"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, List.of(), rulePack);

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedClassFqn()).isEqualTo(
                    "com.quotient.platform.transaction.eval.producer.PatternCheckEventProducer");
            assertThat(f.linkedMethod()).isEqualTo("postPatternCheckEvent");
            assertThat(f.evidenceSource()).isEqualTo("RULE_PACK");
        });
    }

    @Test
    void linkPubSub_usesKafkaRulePackWhenModuleDiffersInMonorepoSuite() {
        var rulePack = new io.testseer.backend.config.MessagingRulePack(
                List.of(), List.of(), java.util.Map.of(), List.of(), List.of(), List.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(), java.util.Map.of(),
                List.of(),
                List.of(
                        new io.testseer.backend.config.MessagingRulePack.KafkaClassLinkRule(
                                "transaction-eval-consumer",
                                "QUOT.REBATE.REDEEM.EVENTS",
                                "PUBLISH",
                                "com.quotient.platform.evaluation.common.helper.TransactionHelper",
                                "postRedeemAndPayoutEvent")),
                List.of(), List.of(), List.of(),
                io.testseer.backend.config.MessagingRulePack.CrossRepoTraceRule.empty());

        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "QUOT.REBATE.REDEEM.EVENTS", "dev", "dev", null, null,
                "PUBLISH", "kafka.topics.rebate.redeem.topic-name",
                "evaluation-domain/evaluation-common/src/main/resources/application-dev.yaml",
                "evaluation-common", null, null, null,
                "YAML_KAFKA", 0.95, "{\"transport\":\"KAFKA\"}"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, List.of(), rulePack);

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedClassFqn()).isEqualTo(
                    "com.quotient.platform.evaluation.common.helper.TransactionHelper");
            assertThat(f.evidenceSource()).isEqualTo("RULE_PACK");
            assertThat(f.confidence()).isEqualTo(0.97);
        });
    }

    @Test
    void linkPubSub_linksSyncProducerKafkaPublisher() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "QUOT.REBATE.REDEEM.EVENTS", "dev", "dev", null, null,
                "PUBLISH", "kafka.topics.rebate.redeem.topic-name",
                "user-profile-service/kubernetes-manifests/dev/application.yaml",
                "user-profile-service", null, null, null,
                "YAML_KAFKA", 0.95, "{\"transport\":\"KAFKA\"}"));

        var java = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "user-profile-service/src/main/java/com/quotient/platform/userprofile/producer/UserEmailAcceptanceRedeemEventProducer.java",
                "class UserEmailAcceptanceRedeemEventProducer { "
                        + "@Qualifier(\"rebateRedeemSyncProducer\") SyncProducer rebateRedeemSyncProducer; "
                        + "publishEvent() { rebateRedeemSyncProducer.send(qMsgEvent); } }",
                "com.quotient.platform.userprofile.producer.UserEmailAcceptanceRedeemEventProducer"));

        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(pubsub, java);

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedClassFqn()).isEqualTo(
                    "com.quotient.platform.userprofile.producer.UserEmailAcceptanceRedeemEventProducer");
            assertThat(f.linkedMethod()).isEqualTo("publishEvent");
            assertThat(f.evidenceSource()).isEqualTo("JAVA_INFERRED");
        });
    }

    @Test
    void linkSchemasToTopics_resolvesTopicFromPubSub() {
        var schemas = List.of(new FactBatch.MessageSchemaFact(
                "QMsgEvent", "OfferEvents.OfferUpdateEvent", "[]", null,
                "com.example.OfferUpdatePublisher", null, "OUTBOUND",
                null, null, "OfferUpdate.proto", "JAVA_INFERRED", 0.85));

        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.OFFER_UPDATE", "pdn", "pdn", null, null,
                "PUBLISH", null, "application-pdn.yaml", "offer-events-consumer",
                "com.example.OfferUpdatePublisher", null, null, "YAML", 1.0, null));

        List<FactBatch.MessageSchemaFact> linked = linker.linkSchemasToTopics(schemas, pubsub);

        assertThat(linked).anyMatch(s -> "PDN_T.OFFER_UPDATE".equals(s.topicShortId()));
    }
}

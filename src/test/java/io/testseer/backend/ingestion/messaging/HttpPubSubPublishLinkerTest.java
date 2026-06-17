package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpPubSubPublishLinkerTest {

    private final HttpPubSubPublishLinker linker = new HttpPubSubPublishLinker();

    @Test
    void link_emitsHttpPubSubPublishFactWithCaller() {
        String yaml = """
                rest:
                  apis:
                    pubsub:
                      uri: http://10.212.11.202:80/pubsub/service/publish
                      method: POST
                      topic-name: DEV_T.NOTIFICATION_REQ
                """;

        String processor = """
                package com.quotient.platform.transaction.eval.processors;
                public class ReceiptTxnEvalProcessor {
                    private com.quotient.platform.transaction.eval.client.PubSubNotificationClient pubSubNotificationClient;
                    public void postNotifications() {
                        pubSubNotificationClient.callNotificationAPI(null, null);
                    }
                }
                """;

        String client = """
                package com.quotient.platform.transaction.eval.client;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties("rest.apis.pubsub")
                public class PubSubNotificationClient {}
                """;

        MessagingRulePack rulePack = new MessagingRulePack(
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), Map.of(), List.of(), List.of(),
                List.of(new MessagingRulePack.HttpPubSubPublishLinkRule(
                        "rest.apis.pubsub.uri",
                        "rest.apis.pubsub.topic-name",
                        "PubSubNotificationClient",
                        "callNotificationAPI",
                        "EVAL_NOTIFICATION",
                        Map.of())),
                List.of(), List.of(), MessagingRulePack.CrossRepoTraceRule.empty());

        List<FactBatch.PubSubResourceFact> facts = linker.link(
                List.of(
                        source("transaction-eval-consumer/src/main/java/processors/ReceiptTxnEvalProcessor.java",
                                processor, "com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor"),
                        source("transaction-eval-consumer/src/main/java/client/PubSubNotificationClient.java",
                                client, "com.quotient.platform.transaction.eval.client.PubSubNotificationClient")),
                List.of(
                        model("com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor"),
                        model("com.quotient.platform.transaction.eval.client.PubSubNotificationClient")),
                List.of(new YamlPubSubExtractor.ConfigFile(
                        "transaction-eval-consumer/src/main/resources/application-dev.yml", yaml)),
                List.of(),
                List.of(),
                rulePack);

        assertThat(facts).hasSize(1);
        FactBatch.PubSubResourceFact fact = facts.get(0);
        assertThat(fact.shortId()).isEqualTo("DEV_T.NOTIFICATION_REQ");
        assertThat(fact.role()).isEqualTo("PUBLISH");
        assertThat(fact.fullResourceId()).contains("/pubsub/service/publish");
        assertThat(fact.linkedClassFqn())
                .isEqualTo("com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor");
        assertThat(fact.linkedMethod()).isEqualTo("postNotifications");
        assertThat(fact.evidenceSource()).isEqualTo("HTTP_PUBSUB_LINKER");
        assertThat(fact.attributes()).contains("\"transport\":\"HTTP_PUBSUB\"");
    }

    @Test
    void link_emitsOneFactPerCallerClassForSameTopic() {
        String yaml = """
                rest:
                  apis:
                    pubsub:
                      uri: http://10.212.11.202:80/pubsub/service/publish
                      topic-name: DEV_T.NOTIFICATION_REQ
                """;

        String receiptProcessor = """
                package com.quotient.platform.transaction.eval.processors;
                public class ReceiptTxnEvalProcessor {
                    private com.quotient.platform.transaction.eval.client.PubSubNotificationClient pubSubNotificationClient;
                    public void notify() {
                        pubSubNotificationClient.callNotificationAPI(null, null, null, null, null, null);
                    }
                }
                """;

        String correctedProcessor = """
                package com.quotient.platform.transaction.eval.processors;
                public class CorrectedTxnEvalProcessor {
                    private com.quotient.platform.transaction.eval.client.PubSubNotificationClient pubSubNotificationClient;
                    public void notify() {
                        pubSubNotificationClient.callNotificationAPI(null, null, null, null, null, null);
                    }
                }
                """;

        String client = """
                package com.quotient.platform.transaction.eval.client;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties("rest.apis.pubsub")
                public class PubSubNotificationClient {}
                """;

        MessagingRulePack rulePack = new MessagingRulePack(
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), Map.of(), List.of(), List.of(),
                List.of(new MessagingRulePack.HttpPubSubPublishLinkRule(
                        "rest.apis.pubsub.uri",
                        "rest.apis.pubsub.topic-name",
                        "PubSubNotificationClient",
                        "callNotificationAPI",
                        "EVAL_NOTIFICATION",
                        Map.of())),
                List.of(), List.of(), MessagingRulePack.CrossRepoTraceRule.empty());

        List<FactBatch.PubSubResourceFact> facts = linker.link(
                List.of(
                        source("transaction-eval-consumer/src/main/java/processors/ReceiptTxnEvalProcessor.java",
                                receiptProcessor,
                                "com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor"),
                        source("transaction-eval-consumer/src/main/java/processors/CorrectedTxnEvalProcessor.java",
                                correctedProcessor,
                                "com.quotient.platform.transaction.eval.processors.CorrectedTxnEvalProcessor"),
                        source("transaction-eval-consumer/src/main/java/client/PubSubNotificationClient.java",
                                client, "com.quotient.platform.transaction.eval.client.PubSubNotificationClient")),
                List.of(
                        model("com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor"),
                        model("com.quotient.platform.transaction.eval.processors.CorrectedTxnEvalProcessor"),
                        model("com.quotient.platform.transaction.eval.client.PubSubNotificationClient")),
                List.of(new YamlPubSubExtractor.ConfigFile(
                        "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml#application.yaml",
                        yaml)),
                List.of(),
                List.of(),
                rulePack);

        assertThat(facts).hasSize(2);
        assertThat(facts)
                .extracting(FactBatch.PubSubResourceFact::linkedClassFqn)
                .containsExactlyInAnyOrder(
                        "com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor",
                        "com.quotient.platform.transaction.eval.processors.CorrectedTxnEvalProcessor");
    }

    private static MessagingFactOrchestrator.SourceFile source(String path, String content, String fqn) {
        ParsedModel model = ParsedModel.of(path, fqn, List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());
        return new MessagingFactOrchestrator.SourceFile(path, content, model);
    }

    private static ParsedModel model(String fqn) {
        return ParsedModel.of("path", fqn, List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());
    }
}

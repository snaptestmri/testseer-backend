package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowGateExtractorTest {

    private final FlowGateExtractor extractor =
            new FlowGateExtractor(MessagingTestFixtures.quotientRulePackLoader());

    @Test
    void extract_detectsFreedomInsertedByGate() {
        String java = """
                package com.example.adapter;
                public class HyveeOfferAdapter {
                  private static final String FREEDOM = "FREEDOM";
                  public void process(Offer o) {
                    if (!FREEDOM.equalsIgnoreCase(o.getInsertedBy())) {
                      return;
                    }
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "HyveeOfferAdapter.java", java, "com.example.adapter.HyveeOfferAdapter"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "BUSINESS_RULE".equals(g.gateKind())
                        && "insertedBy".equals(g.gateKey())
                        && "FREEDOM".equals(g.requiredValue())
                        && "HYVEE_ADAPTER".equals(g.guardedFlowStep()));
    }

    @Test
    void extract_detectsYamlEnabledFlag() {
        String yaml = """
                partner-notify-enabled: true
                offer-update-enabled: false
                """;
        var yamlFiles = List.of(new YamlPubSubExtractor.ConfigFile(
                "offer-events/src/main/resources/application-pdn.yaml", yaml));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), List.of(), yamlFiles);

        assertThat(gates).anyMatch(g ->
                "YAML_FLAG".equals(g.gateKind()) && g.gateKey().contains("enabled"));
    }

    @Test
    void extract_detectsConditionalOnProperty() {
        var model = ParsedModel.of(
                "Publisher.java", "com.example.OfferPublisher",
                List.of("@ConditionalOnProperty(\"pubsub.publisher.enabled\")"),
                List.of(), List.of(), List.of(), List.of(), false, null, null, List.of(), List.of());

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(model), List.of(), List.of());

        assertThat(gates).anyMatch(g ->
                "CONDITIONAL_BEAN".equals(g.gateKind())
                        && "pubsub.publisher.enabled".equals(g.gateKey()));
    }

    @Test
    void extract_detectsIsPublishedStreamFilter() {
        String java = """
                package com.example.galo;
                public class OfferService {
                  public void getPidOfferDetails() {
                    offerIds = offerIds.stream()
                        .filter(key -> pidOfferMap.get(key).getIsPublished())
                        .collect(Collectors.toList());
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "OfferService.java", java, "com.example.galo.OfferService"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "BUSINESS_RULE".equals(g.gateKind())
                        && g.gateKey().contains("IsPublished")
                        && "true".equals(g.requiredValue())
                        && "SKIP".equals(g.effectWhenFail()));
    }

    @Test
    void extract_detectsBannerPublishStatusFilterDelegate() {
        String java = """
                package com.example.galo;
                public class OfferService {
                  private void processOfferPidMap(OfferPidMap offerPidMap) {
                    if (!publishStatusFilter.filter(offerPidMap)) {
                      banners.add(banner);
                    }
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "OfferService.java", java, "com.example.galo.OfferService"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "BUSINESS_RULE".equals(g.gateKind())
                        && g.gateKey().contains("OfferPidMap"));
    }

    @Test
    void extract_detectsPreLiveActiveOfferFilter() {
        String java = """
                package com.example.galo;
                public class OfferFilterService {
                  void buildChain() {
                    offerChain.add(ActiveOfferFilter.instance(
                        excludedStates,
                        filters != null ? filters.get(FilterEnum.preLive) : null,
                        currentDateTimeInUserTimezone, timezoneOffset));
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "OfferFilterService.java", java, "com.example.galo.OfferFilterService"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "BUSINESS_RULE".equals(g.gateKind())
                        && "Offer.preLive".equals(g.gateKey()));
    }

    @Test
    void extract_doesNotConfuseWriteDefaultWithReadGate() {
        String java = """
                package com.example.ois;
                public class PartnerPublishingService {
                  void publish(Item item) {
                    if (item.getIsPublished() == null) {
                      item.setIsPublished(true);
                    }
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "PartnerPublishingService.java", java, "com.example.ois.PartnerPublishingService"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).noneMatch(g ->
                "BUSINESS_RULE".equals(g.gateKind()) && g.gateKey().contains("IsPublished"));
    }

    @Test
    void extract_detectsSystemConfigKeys_partnerScoped() {
        String java = """
                package com.quotient.platform.transaction.eval.service;
                public class TransactionEvaluationService {
                  void evaluate() {
                    if (systemConfigService.isConfigEnabled(
                            transactionEvent.getTransactionHeader().getPartnerId(),
                            SystemConfigKeys.SkipEvaluationEnabled.name())) {
                      return;
                    }
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "TransactionEvaluationService.java", java,
                "com.quotient.platform.transaction.eval.service.TransactionEvaluationService"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "SYSTEM_CONFIG".equals(g.gateKind())
                        && "SkipEvaluationEnabled".equals(g.gateKey())
                        && "EVAL_STC".equals(g.guardedFlowStep())
                        && g.testPrecondition().contains("partner"));
    }

    @Test
    void extract_detectsConditionalOnPropertyFromSource() {
        String java = """
                package com.quotient.platform.transaction.eval.consumer;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                @ConditionalOnProperty("kafka.topics.stxn.pipeline.enabled")
                public class TransactionEvalConsumer {
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "TransactionEvalConsumer.java", java,
                "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "CONDITIONAL_BEAN".equals(g.gateKind())
                        && "kafka.topics.stxn.pipeline.enabled".equals(g.gateKey())
                        && "EVAL_STC".equals(g.guardedFlowStep()));
    }

    @Test
    void extract_detectsYamlConfigMapEnabledFlag() {
        String yaml = """
                apiVersion: v1
                kind: ConfigMap
                data:
                  application.yaml: |
                    kafka:
                      topics:
                        stxn:
                          pipeline:
                            enabled: true
                """;
        var yamlFiles = List.of(new YamlPubSubExtractor.ConfigFile(
                "transaction-eval-consumer/k8s/transaction-eval-consumer.dev.config-map.yaml", yaml));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), List.of(), yamlFiles);

        assertThat(gates).anyMatch(g ->
                "YAML_FLAG".equals(g.gateKind())
                        && "kafka.topics.stxn.pipeline.enabled".equals(g.gateKey())
                        && g.yamlPath().contains("application.yaml"));
    }

    @Test
    void extract_dedup_trustedRedemption_over_isTrustedPartner() {
        String java = """
                package com.quotient.platform.transaction.eval.processors;
                public class DefaultTxnEvalProcessor {
                  void run() {
                    boolean isTrustedPartner = systemConfigService.isConfigEnabled(
                        qMsgEvent.getAffiliatePartnerId(),
                        SystemConfigKeys.TrustedRedemptionEnabled.name());
                    if (!isTrustedPartner) {
                      return;
                    }
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "DefaultTxnEvalProcessor.java", java,
                "com.quotient.platform.transaction.eval.processors.DefaultTxnEvalProcessor"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "SYSTEM_CONFIG".equals(g.gateKind())
                        && "TrustedRedemptionEnabled".equals(g.gateKey()));
        assertThat(gates).noneMatch(g ->
                "CODE_FLAG".equals(g.gateKind()) && "isTrustedPartner=true".equals(g.gateKey()));
    }

    @Test
    void extract_fraudRulesDynamicKey_fromRulePack() {
        String java = """
                package com.quotient.platform.transaction.eval.helper;
                public class FraudRulesEvaluationHelper {
                  void send() {
                    SystemConfigKeys fraudRules = isSTC ?
                        SystemConfigKeys.STCTransactionFraudRules : SystemConfigKeys.TransactionFraudRules;
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "FraudRulesEvaluationHelper.java", java,
                "com.quotient.platform.transaction.eval.helper.FraudRulesEvaluationHelper"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "SYSTEM_CONFIG".equals(g.gateKind())
                        && "TransactionFraudRules".equals(g.gateKey()));
        assertThat(gates).anyMatch(g ->
                "SYSTEM_CONFIG".equals(g.gateKind())
                        && "STCTransactionFraudRules".equals(g.gateKey()));
    }

    @Test
    void extract_declaredGate_forConditionalStackingHelper() {
        String java = """
                package com.quotient.platform.transaction.eval.helper;
                public class ConditionalOfferStackingHelper {
                  private static final String KEY = "CONDITIONAL_STACKING_OFFERIDS";
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "ConditionalOfferStackingHelper.java", java,
                "com.quotient.platform.transaction.eval.helper.ConditionalOfferStackingHelper"));

        List<FactBatch.FlowGateFact> gates = extractor.extract(List.of(), javaFiles, List.of());

        assertThat(gates).anyMatch(g ->
                "SYSTEM_CONFIG".equals(g.gateKind())
                        && "CONDITIONAL_STACKING_OFFERIDS".equals(g.gateKey())
                        && "RULE_PACK".equals(g.evidenceSource()));
    }
}

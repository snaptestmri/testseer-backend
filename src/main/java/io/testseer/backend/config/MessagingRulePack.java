package io.testseer.backend.config;

import java.util.List;
import java.util.Map;

public record MessagingRulePack(
        List<TopicFlowStepRule> topicFlowSteps,
        List<ClassFlowStepRule> classFlowSteps,
        Map<String, DbTableHintRule> dbTableHints,
        List<CodeGateRule> codeGateRules,
        List<ClassFlowStepRule> classFlowStepRules,
        List<PartnerEndpointRule> partnerEndpoints,
        Map<String, ExternalEndpointHintRule> externalEndpointHints,
        Map<String, DataObjectRule> dataObjects,
        Map<String, ConsistencyRule> consistencyRules,
        Map<String, GateKeyAliasRule> gateKeyAliases,
        List<TerminalRetryPathRule> terminalRetryPaths,
        Map<String, SubscriptionTopicRule> subscriptionTopicMap,
        List<PubSubClassLinkRule> pubSubClassLinks,
        List<KafkaClassLinkRule> kafkaClassLinks,
        List<HttpPubSubPublishLinkRule> httpPubSubPublishLinks,
        List<DeclaredGateRule> declaredGates,
        List<KafkaTopicAliasRule> kafkaTopicAliases,
        CrossRepoTraceRule crossRepoTrace
) {
    public static MessagingRulePack empty() {
        return new MessagingRulePack(
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                CrossRepoTraceRule.empty());
    }

    /** Curated override when Java/module inference cannot resolve pub/sub → handler class. */
    public record PubSubClassLinkRule(
            String module,
            String springKeyLeaf,
            String role,
            String classFqn,
            String method
    ) {}

    /** Curated override for Kafka topic → handler class (BL-050). */
    public record KafkaClassLinkRule(
            String module,
            String topicShortId,
            String role,
            String classFqn,
            String method
    ) {}

    /** Logical Kafka topic id and env-specific aliases for cross-repo trace (BL-050 P0). */
    public record KafkaTopicAliasRule(String logical, List<String> aliases) {}

    /** HTTP pubsub publish API → logical topic (BL-051). */
    public record HttpPubSubPublishLinkRule(
            String configUriKey,
            String configTopicKey,
            String clientClass,
            String callerPattern,
            String flowStep,
            Map<String, List<String>> topicAliases
    ) {}

    public record SubscriptionTopicRule(String topicShortId) {}

    public record TopicFlowStepRule(String match, String flowStep) {}

    public record ClassFlowStepRule(String match, String flowStep) {}

    public record DbTableHintRule(String hintValue) {}

    public record CodeGateRule(
            String pattern,
            String flowStep,
            String gateKind,
            String gateKey,
            Integer gateKeyFromGroup,
            String requiredValue,
            Integer requiredValueFromGroup,
            String operator,
            String effectWhenFail,
            String testPrecondition,
            String testPreconditionTemplate,
            double confidence
    ) {}

    /** Curated gate when code shape is dynamic or uses string constants (BL-052 pillar D). */
    public record DeclaredGateRule(
            String classFqn,
            String flowStep,
            String gateKind,
            String gateKey,
            String requiredValue,
            String operator,
            String effectWhenFail,
            String testPrecondition,
            double confidence
    ) {}

    public record PartnerEndpointRule(
            String match,
            String partner,
            String operation,
            String flowStep,
            String boundary,
            String clientClass,
            String httpMethod,
            String authScheme,
            List<String> configKeys
    ) {}

    public record ExternalEndpointHintRule(List<String> hints) {}

    public record DataObjectMirrorRule(String storeType, String physicalName, String pollNote) {}

    public record DataObjectRule(
            String storeType,
            String physicalName,
            String entityFqn,
            String domainFqn,
            String accessorFqn,
            List<String> methods,
            List<String> correlationKeys,
            String pollHint,
            List<String> flowSteps,
            List<DataObjectMirrorRule> mirrors,
            String pollNote
    ) {}

    public record ConsistencyParticipantRule(
            String storeType,
            String physicalName,
            String role,
            String via,
            String lagClass
    ) {}

    public record ConsistencyInvariantRule(
            String kind,
            String description,
            String pollHint
    ) {}

    public record ConsistencyRule(
            String pattern,
            List<String> flowSteps,
            String primaryStore,
            String primaryPhysical,
            List<ConsistencyParticipantRule> participants,
            List<ConsistencyInvariantRule> invariants,
            List<String> correlationKeys,
            Map<String, Object> pollStrategy,
            String gapStrategy,
            List<ConsistencyParticipantRule> endStateParticipants
    ) {}

    public record GateKeyAliasRule(
            String configTable,
            String configKey,
            boolean envScoped,
            boolean redact
    ) {}

    public record TerminalRetryPathRule(
            String topicMatch,
            String cronJobName,
            String bqTableSuffix,
            String partnerVariant,
            String note
    ) {}

    /** BL-055 / BL-057 cross-repo trace scope and gap taxonomy. */
    public record CrossRepoTraceRule(
            List<String> manifestOnlyRepos,
            List<String> catalogOnlyRepos,
            List<TerminalTopicRule> terminalTopics
    ) {
        public static CrossRepoTraceRule empty() {
            return new CrossRepoTraceRule(List.of(), List.of(), List.of());
        }
    }

    public record TerminalTopicRule(
            String id,
            String pattern,
            String boundary,
            String note
    ) {}
}

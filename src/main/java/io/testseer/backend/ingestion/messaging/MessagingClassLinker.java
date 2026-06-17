package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MessagingClassLinker {

    public List<FactBatch.PubSubResourceFact> linkPubSub(
            List<FactBatch.PubSubResourceFact> pubsubFacts,
            List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        return linkPubSub(pubsubFacts, javaFiles, MessagingRulePack.empty());
    }

    public List<FactBatch.PubSubResourceFact> linkPubSub(
            List<FactBatch.PubSubResourceFact> pubsubFacts,
            List<ProtoSchemaExtractor.JavaSourceFile> javaFiles,
            MessagingRulePack rulePack) {

        Map<String, List<ProtoSchemaExtractor.JavaSourceFile>> byModule = javaFiles.stream()
                .filter(f -> f.classFqn() != null)
                .collect(Collectors.groupingBy(f -> moduleOf(f.path())));

        List<ProtoSchemaExtractor.JavaSourceFile> allFiles = javaFiles.stream()
                .filter(f -> f.classFqn() != null)
                .toList();

        List<FactBatch.PubSubResourceFact> linked = new ArrayList<>();
        for (FactBatch.PubSubResourceFact fact : pubsubFacts) {
            LinkResult result = resolveLink(fact, byModule, allFiles, rulePack);
            if (result.linkedClassFqn() != null || fact.linkedClassFqn() != null) {
                linked.add(new FactBatch.PubSubResourceFact(
                        fact.resourceKind(), fact.shortId(), fact.envLane(), fact.envProfile(),
                        fact.gcpProject(), fact.fullResourceId(), fact.role(), fact.springKey(),
                        fact.yamlPath(), fact.moduleName(),
                        result.linkedClassFqn() != null ? result.linkedClassFqn() : fact.linkedClassFqn(),
                        result.linkedMethod() != null ? result.linkedMethod() : fact.linkedMethod(),
                        fact.workloadName(), result.evidenceSource(), result.confidence(), fact.attributes()
                ));
            } else {
                linked.add(fact);
            }
        }
        return linked;
    }

    private LinkResult resolveLink(
            FactBatch.PubSubResourceFact fact,
            Map<String, List<ProtoSchemaExtractor.JavaSourceFile>> byModule,
            List<ProtoSchemaExtractor.JavaSourceFile> allFiles,
            MessagingRulePack rulePack) {

        LinkResult rulePackLink = matchRulePack(fact, rulePack);
        if (rulePackLink != null) {
            return rulePackLink;
        }

        LinkResult kafkaRulePackLink = matchKafkaRulePack(fact, rulePack);
        if (kafkaRulePackLink != null) {
            return kafkaRulePackLink;
        }

        String module = fact.moduleName();
        List<ProtoSchemaExtractor.JavaSourceFile> moduleCandidates =
                byModule.getOrDefault(module, List.of());

        LinkResult tier1 = matchCandidates(fact, moduleCandidates, "JAVA_INFERRED", 0.90);
        if (tier1.linkedClassFqn() != null) {
            return tier1;
        }

        LinkResult tier2 = matchCandidates(fact, allFiles, "JAVA_INFERRED", 0.85);
        if (tier2.linkedClassFqn() != null) {
            return tier2;
        }

        return new LinkResult(null, null, fact.evidenceSource(), fact.confidence());
    }

    private static LinkResult matchRulePack(FactBatch.PubSubResourceFact fact, MessagingRulePack rulePack) {
        if (rulePack.pubSubClassLinks() == null || rulePack.pubSubClassLinks().isEmpty()) {
            return null;
        }
        String springLeaf = springKeyLeaf(fact.springKey());
        for (MessagingRulePack.PubSubClassLinkRule rule : rulePack.pubSubClassLinks()) {
            if (rule.role() != null && !rule.role().equalsIgnoreCase(fact.role())) {
                continue;
            }
            if (rule.module() != null && fact.moduleName() != null
                    && !rule.module().equalsIgnoreCase(fact.moduleName())) {
                continue;
            }
            if (rule.springKeyLeaf() != null && springLeaf != null
                    && !rule.springKeyLeaf().equalsIgnoreCase(springLeaf)) {
                continue;
            }
            if (rule.classFqn() != null && !rule.classFqn().isBlank()) {
                return new LinkResult(rule.classFqn(), rule.method(), "RULE_PACK", 0.99);
            }
        }
        return null;
    }

    private static LinkResult matchKafkaRulePack(FactBatch.PubSubResourceFact fact, MessagingRulePack rulePack) {
        if (!YamlKafkaTopicExtractor.isKafkaFact(fact)) return null;
        if (rulePack.kafkaClassLinks() == null || rulePack.kafkaClassLinks().isEmpty()) {
            return null;
        }
        LinkResult strict = matchKafkaRulePackTier(fact, rulePack, true);
        if (strict != null) {
            return strict;
        }
        return matchKafkaRulePackTier(fact, rulePack, false);
    }

    private static LinkResult matchKafkaRulePackTier(
            FactBatch.PubSubResourceFact fact,
            MessagingRulePack rulePack,
            boolean requireModule) {
        for (MessagingRulePack.KafkaClassLinkRule rule : rulePack.kafkaClassLinks()) {
            if (rule.role() != null && !rule.role().equalsIgnoreCase(fact.role())) {
                continue;
            }
            if (requireModule && rule.module() != null && fact.moduleName() != null
                    && !rule.module().equalsIgnoreCase(fact.moduleName())) {
                continue;
            }
            if (rule.topicShortId() != null && fact.shortId() != null
                    && !rule.topicShortId().equalsIgnoreCase(fact.shortId())) {
                continue;
            }
            if (rule.classFqn() != null && !rule.classFqn().isBlank()) {
                double confidence = requireModule ? 0.99 : 0.97;
                return new LinkResult(rule.classFqn(), rule.method(), "RULE_PACK", confidence);
            }
        }
        return null;
    }

    private static LinkResult matchCandidates(
            FactBatch.PubSubResourceFact fact,
            List<ProtoSchemaExtractor.JavaSourceFile> candidates,
            String evidenceSource,
            double baseConfidence) {
        if (candidates.isEmpty()) {
            return new LinkResult(null, null, fact.evidenceSource(), fact.confidence());
        }

        if ("PUBLISH".equals(fact.role())) {
            if (YamlKafkaTopicExtractor.isKafkaFact(fact)) {
                for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                    if (isKafkaProducer(jf) && contentMentionsKey(jf.content(), fact.springKey())) {
                        return new LinkResult(jf.classFqn(), inferPublishMethod(jf), evidenceSource, 0.95);
                    }
                }
                for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                    if (isKafkaProducer(jf) && contentMentionsTopic(jf.content(), fact.shortId())) {
                        return new LinkResult(jf.classFqn(), inferPublishMethod(jf), evidenceSource, baseConfidence);
                    }
                }
                for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                    if (isKafkaProducer(jf)) {
                        return new LinkResult(jf.classFqn(), inferPublishMethod(jf), evidenceSource, baseConfidence - 0.05);
                    }
                }
            }
            for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                if (isPublisher(jf) && contentMentionsKey(jf.content(), fact.springKey())) {
                    return new LinkResult(jf.classFqn(), inferPublishMethod(jf), evidenceSource, 0.95);
                }
            }
            for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                if (isPublisher(jf) && contentMentionsSpringLeaf(jf.content(), fact.springKey())) {
                    return new LinkResult(jf.classFqn(), inferPublishMethod(jf), evidenceSource, baseConfidence);
                }
            }
            for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                if (isPublisher(jf)) {
                    return new LinkResult(jf.classFqn(), inferPublishMethod(jf), evidenceSource, baseConfidence - 0.05);
                }
            }
        } else if ("SUBSCRIBE".equals(fact.role())) {
            if (YamlKafkaTopicExtractor.isKafkaFact(fact)) {
                for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                    if (isKafkaConsumer(jf)) {
                        String method = inferKafkaListenerMethod(jf);
                        return new LinkResult(jf.classFqn(), method, evidenceSource, baseConfidence);
                    }
                }
            }
            for (ProtoSchemaExtractor.JavaSourceFile jf : candidates) {
                if (isConsumer(jf.classFqn())) {
                    return new LinkResult(jf.classFqn(), "onMessage", evidenceSource, baseConfidence);
                }
            }
        }
        return new LinkResult(null, null, fact.evidenceSource(), fact.confidence());
    }

    private static String inferPublishMethod(ProtoSchemaExtractor.JavaSourceFile jf) {
        if (jf.content() != null && jf.content().contains("sendUpdateManageOfferEvent")) {
            return "sendUpdateManageOfferEvent";
        }
        if (jf.content() != null && jf.content().contains("publishEvent")) {
            return "publishEvent";
        }
        return "publishEvent";
    }

    private record LinkResult(String linkedClassFqn, String linkedMethod, String evidenceSource, double confidence) {}

    public List<FactBatch.MessageSchemaFact> linkSchemasToTopics(
            List<FactBatch.MessageSchemaFact> schemas,
            List<FactBatch.PubSubResourceFact> pubsubFacts) {

        List<FactBatch.MessageSchemaFact> linked = new ArrayList<>();
        for (FactBatch.MessageSchemaFact schema : schemas) {
            String topic = schema.topicShortId();
            if (topic == null && schema.linkedClassFqn() != null) {
                topic = pubsubFacts.stream()
                        .filter(p -> schema.linkedClassFqn().equals(p.linkedClassFqn()))
                        .map(FactBatch.PubSubResourceFact::shortId)
                        .findFirst()
                        .orElse(null);
            }
            linked.add(new FactBatch.MessageSchemaFact(
                    schema.envelopeType(), schema.payloadProto(), schema.payloadFields(),
                    schema.payloadEnums(), schema.linkedClassFqn(), schema.linkedMethod(),
                    schema.direction(), topic, schema.unpackExpression(), schema.protoFile(),
                    schema.evidenceSource(), topic != null ? 0.95 : schema.confidence()
            ));
        }
        return linked;
    }

    private static boolean isPublisher(ProtoSchemaExtractor.JavaSourceFile jf) {
        return isPublisherFqn(jf.classFqn()) || isPublishService(jf);
    }

    private static boolean isPublisherFqn(String fqn) {
        return fqn != null && fqn.contains("Publisher");
    }

    private static boolean isPublishService(ProtoSchemaExtractor.JavaSourceFile jf) {
        if (jf.classFqn() == null || !simpleName(jf.classFqn()).endsWith("PublishService")) {
            return false;
        }
        String content = jf.content();
        return content != null
                && (content.contains("PubSubMsgGateway") || content.contains("publishGateway"));
    }

    private static boolean isConsumer(String fqn) {
        if (fqn == null) {
            return false;
        }
        String simple = simpleName(fqn);
        return simple.endsWith("Consumer") && !simple.endsWith("ConsumerApplication");
    }

    private static boolean isKafkaConsumer(ProtoSchemaExtractor.JavaSourceFile jf) {
        return isConsumer(jf.classFqn())
                && jf.content() != null
                && jf.content().contains("@KafkaListener");
    }

    private static boolean isKafkaProducer(ProtoSchemaExtractor.JavaSourceFile jf) {
        if (isPublisher(jf)) return true;
        if (jf.classFqn() == null || jf.content() == null) return false;
        String simple = simpleName(jf.classFqn());
        return (simple.endsWith("EventProducer") || simple.endsWith("Producer"))
                && (jf.content().contains("SyncProducer")
                || jf.content().contains("AsyncProducer")
                || jf.content().contains("KafkaTemplate"));
    }

    private static String inferKafkaListenerMethod(ProtoSchemaExtractor.JavaSourceFile jf) {
        if (jf.content() == null) return "onMessage";
        if (jf.content().contains("processSalesCanonicalEvent")) return "processSalesCanonicalEvent";
        if (jf.content().contains("publishEvent")) return "publishEvent";
        return "onMessage";
    }

    private static boolean contentMentionsTopic(String content, String topicShortId) {
        return topicShortId != null && content != null && content.contains(topicShortId);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static boolean contentMentionsKey(String content, String springKey) {
        if (springKey == null || content == null) return false;
        String leaf = springKeyLeaf(springKey);
        return content.contains(leaf) || content.contains(springKey);
    }

    private static boolean contentMentionsSpringLeaf(String content, String springKey) {
        if (springKey == null || content == null) return false;
        String leaf = springKeyLeaf(springKey);
        return leaf != null
                && (content.contains("\"" + leaf + "\"") || content.contains("'" + leaf + "'"));
    }

    private static String springKeyLeaf(String springKey) {
        if (springKey == null || springKey.isBlank()) {
            return null;
        }
        return springKey.substring(springKey.lastIndexOf('.') + 1);
    }

    private String moduleOf(String path) {
        return EnvLaneResolver.resolveModuleName(path);
    }
}

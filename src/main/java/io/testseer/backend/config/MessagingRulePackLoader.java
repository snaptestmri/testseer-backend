package io.testseer.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MessagingRulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(MessagingRulePackLoader.class);

    private final Resource rulePackPath;
    private final AtomicReference<MessagingRulePack> rulePack;

    public MessagingRulePackLoader(
            @Value("${testseer.messaging.rule-pack-path:file:../config/rule-packs/quotient-messaging.yml}") Resource rulePackPath) {
        this.rulePackPath = rulePackPath;
        this.rulePack = new AtomicReference<>(load(rulePackPath));
    }

    public MessagingRulePack getRulePack() {
        return rulePack.get();
    }

    public void reload() {
        rulePack.set(load(rulePackPath));
    }

    @SuppressWarnings("unchecked")
    private MessagingRulePack load(Resource resource) {
        try (InputStream in = openStream(resource)) {
            if (in == null) {
                log.info("Messaging rule pack not found at {}; using generic extraction only", resource);
                return MessagingRulePack.empty();
            }
            Map<String, Object> raw = new Yaml().load(in);
            if (raw == null) {
                return MessagingRulePack.empty();
            }

            List<MessagingRulePack.TopicFlowStepRule> topicRules = list(raw.get("topicFlowSteps")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new MessagingRulePack.TopicFlowStepRule(
                            string(m.get("match")), string(m.get("flowStep"))))
                    .toList();

            List<MessagingRulePack.ClassFlowStepRule> classRules = list(raw.get("classFlowSteps")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new MessagingRulePack.ClassFlowStepRule(
                            string(m.get("match")), string(m.get("flowStep"))))
                    .toList();

            Map<String, MessagingRulePack.DbTableHintRule> dbHints = new LinkedHashMap<>();
            Object dbRaw = raw.get("dbTableHints");
            if (dbRaw instanceof Map<?, ?> dbMap) {
                dbMap.forEach((table, hintDef) -> {
                    if (hintDef instanceof Map<?, ?> hint) {
                        dbHints.put(String.valueOf(table), new MessagingRulePack.DbTableHintRule(
                                string(hint.get("hintValue"))));
                    }
                });
            }

            List<MessagingRulePack.CodeGateRule> codeRules = list(raw.get("codeGateRules")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new MessagingRulePack.CodeGateRule(
                            string(m.get("pattern")),
                            string(m.get("flowStep")),
                            string(m.get("gateKind")),
                            string(m.get("gateKey")),
                            intOrNull(m.get("gateKeyFromGroup")),
                            string(m.get("requiredValue")),
                            intOrNull(m.get("requiredValueFromGroup")),
                            string(m.get("operator")),
                            string(m.get("effectWhenFail")),
                            string(m.get("testPrecondition")),
                            string(m.get("testPreconditionTemplate")),
                            doubleOrDefault(m.get("confidence"), 0.90)
                    ))
                    .toList();

            List<MessagingRulePack.ClassFlowStepRule> classFlowStepRules = list(raw.get("classFlowStepRules")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new MessagingRulePack.ClassFlowStepRule(
                            string(m.get("match")), string(m.get("flowStep"))))
                    .toList();

            List<MessagingRulePack.PartnerEndpointRule> partnerEndpoints = list(raw.get("partnerEndpoints")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new MessagingRulePack.PartnerEndpointRule(
                            string(m.get("match")),
                            string(m.get("partner")),
                            string(m.get("operation")),
                            string(m.get("flowStep")),
                            string(m.get("boundary")),
                            string(m.get("clientClass")),
                            string(m.get("httpMethod")),
                            string(m.get("authScheme")),
                            stringList(m.get("configKeys"))
                    ))
                    .toList();

            Map<String, MessagingRulePack.ExternalEndpointHintRule> externalHints = new LinkedHashMap<>();
            Object hintsRaw = raw.get("externalEndpointHints");
            if (hintsRaw instanceof Map<?, ?> hintsMap) {
                hintsMap.forEach((key, hintDef) -> {
                    if (hintDef instanceof Map<?, ?> hint) {
                        externalHints.put(String.valueOf(key),
                                new MessagingRulePack.ExternalEndpointHintRule(stringList(hint.get("hints"))));
                    }
                });
            }

            Map<String, MessagingRulePack.DataObjectRule> dataObjects = loadDataObjects(raw.get("dataObjects"));
            migrateDbTableHints(dbHints, dataObjects);
            Map<String, MessagingRulePack.ConsistencyRule> consistencyRules =
                    loadConsistencyRules(raw.get("consistencyRules"));
            Map<String, MessagingRulePack.GateKeyAliasRule> gateKeyAliases = loadGateKeyAliases(raw.get("gateKeyAliases"));
            List<MessagingRulePack.TerminalRetryPathRule> terminalRetryPaths =
                    loadTerminalRetryPaths(raw.get("terminalRetryPaths"));
            Map<String, MessagingRulePack.SubscriptionTopicRule> subscriptionTopicMap =
                    loadSubscriptionTopicMap(raw.get("subscriptionTopicMap"));
            List<MessagingRulePack.PubSubClassLinkRule> pubSubClassLinks =
                    loadPubSubClassLinks(raw.get("pubSubClassLinks"));
            List<MessagingRulePack.KafkaClassLinkRule> kafkaClassLinks =
                    loadKafkaClassLinks(raw.get("kafkaClassLinks"));
            List<MessagingRulePack.HttpPubSubPublishLinkRule> httpPubSubPublishLinks =
                    loadHttpPubSubPublishLinks(raw.get("httpPubSubPublishLinks"));
            List<MessagingRulePack.DeclaredGateRule> declaredGates =
                    loadDeclaredGates(raw.get("declaredGates"));
            List<MessagingRulePack.KafkaTopicAliasRule> kafkaTopicAliases =
                    loadKafkaTopicAliases(raw.get("kafkaTopicAliases"));
            MessagingRulePack.CrossRepoTraceRule crossRepoTrace =
                    loadCrossRepoTrace(raw.get("crossRepoTrace"));

            return new MessagingRulePack(
                    topicRules, classRules, dbHints, codeRules, classFlowStepRules,
                    partnerEndpoints, externalHints, dataObjects, consistencyRules, gateKeyAliases,
                    terminalRetryPaths, subscriptionTopicMap, pubSubClassLinks, kafkaClassLinks,
                    httpPubSubPublishLinks, declaredGates, kafkaTopicAliases, crossRepoTrace);
        } catch (Exception ex) {
            log.warn("Failed to load messaging rule pack from {}: {}", resource, ex.getMessage());
            return MessagingRulePack.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MessagingRulePack.DataObjectRule> loadDataObjects(Object raw) {
        Map<String, MessagingRulePack.DataObjectRule> map = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> objects)) return map;
        objects.forEach((key, def) -> {
            if (!(def instanceof Map<?, ?> m)) return;
            List<MessagingRulePack.DataObjectMirrorRule> mirrors = list(m.get("mirrors")).stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    .map(mirror -> new MessagingRulePack.DataObjectMirrorRule(
                            string(mirror.get("storeType")),
                            string(mirror.get("physicalName")),
                            string(mirror.get("pollNote"))))
                    .toList();
            map.put(String.valueOf(key), new MessagingRulePack.DataObjectRule(
                    string(m.get("storeType")),
                    string(m.get("physicalName")),
                    string(m.get("entityFqn")),
                    string(m.get("domainFqn")),
                    string(m.get("accessorFqn")),
                    stringList(m.get("methods")),
                    stringList(m.get("correlationKeys")),
                    string(m.get("pollHint")),
                    stringList(m.get("flowSteps")),
                    mirrors,
                    string(m.get("pollNote"))
            ));
        });
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.ConsistencyParticipantRule> loadParticipantRules(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o)
                .map(p -> new MessagingRulePack.ConsistencyParticipantRule(
                        string(p.get("storeType")),
                        string(p.get("physicalName")),
                        string(p.get("role")),
                        string(p.get("via")),
                        string(p.get("lagClass"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MessagingRulePack.ConsistencyRule> loadConsistencyRules(Object raw) {
        Map<String, MessagingRulePack.ConsistencyRule> map = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> rules)) return map;
        rules.forEach((key, def) -> {
            if (!(def instanceof Map<?, ?> m)) return;
            List<MessagingRulePack.ConsistencyParticipantRule> participants =
                    loadParticipantRules(m.get("participants"));
            List<MessagingRulePack.ConsistencyInvariantRule> invariants = list(m.get("invariants")).stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    .map(inv -> new MessagingRulePack.ConsistencyInvariantRule(
                            string(inv.get("kind")),
                            string(inv.get("description")),
                            string(inv.get("pollHint"))))
                    .toList();
            @SuppressWarnings("unchecked")
            Map<String, Object> pollStrategy = m.get("pollStrategy") instanceof Map<?, ?> ps
                    ? (Map<String, Object>) ps : null;
            String primaryStore = string(m.get("primaryStore"));
            String primaryPhysical = string(m.get("primaryPhysical"));
            if (primaryStore == null && !participants.isEmpty()) {
                primaryStore = participants.get(0).storeType();
            }
            if (primaryPhysical == null && !participants.isEmpty()) {
                primaryPhysical = participants.get(0).physicalName();
            }
            List<MessagingRulePack.ConsistencyParticipantRule> endStateParticipants =
                    loadParticipantRules(m.get("endStateParticipants"));
            map.put(String.valueOf(key), new MessagingRulePack.ConsistencyRule(
                    string(m.get("pattern")),
                    stringList(m.get("flowSteps")),
                    primaryStore,
                    primaryPhysical,
                    participants,
                    invariants,
                    stringList(m.get("correlationKeys")),
                    pollStrategy,
                    string(m.get("gapStrategy")),
                    endStateParticipants
            ));
        });
        return map;
    }

    private static void migrateDbTableHints(
            Map<String, MessagingRulePack.DbTableHintRule> dbHints,
            Map<String, MessagingRulePack.DataObjectRule> dataObjects) {
        dbHints.forEach((tableKey, hint) -> {
            String alias = toPascalCase(tableKey);
            if (dataObjects.containsKey(alias)) return;
            dataObjects.put(alias, new MessagingRulePack.DataObjectRule(
                    null, alias, null, null, null, null, null,
                    hint.hintValue(), null, null, null));
        });
    }

    private static String toPascalCase(String snake) {
        if (snake == null || snake.isBlank()) return snake;
        StringBuilder sb = new StringBuilder();
        for (String part : snake.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MessagingRulePack.GateKeyAliasRule> loadGateKeyAliases(Object raw) {
        Map<String, MessagingRulePack.GateKeyAliasRule> map = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> aliases)) return map;
        aliases.forEach((gateKey, def) -> {
            if (!(def instanceof Map<?, ?> m)) return;
            map.put(String.valueOf(gateKey), new MessagingRulePack.GateKeyAliasRule(
                    string(m.get("configTable")),
                    string(m.get("configKey")),
                    boolOrDefault(m.get("envScoped"), true),
                    boolOrDefault(m.get("redact"), false)
            ));
        });
        return map;
    }

    private static boolean boolOrDefault(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MessagingRulePack.SubscriptionTopicRule> loadSubscriptionTopicMap(Object raw) {
        Map<String, MessagingRulePack.SubscriptionTopicRule> map = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> entries)) return map;
        entries.forEach((subId, def) -> {
            if (def instanceof Map<?, ?> m) {
                map.put(String.valueOf(subId), new MessagingRulePack.SubscriptionTopicRule(
                        string(m.get("topicShortId"))));
            } else if (def instanceof String topicShortId) {
                map.put(String.valueOf(subId), new MessagingRulePack.SubscriptionTopicRule(topicShortId));
            }
        });
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.PubSubClassLinkRule> loadPubSubClassLinks(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.PubSubClassLinkRule(
                        string(m.get("module")),
                        string(m.get("springKeyLeaf")),
                        string(m.get("role")),
                        string(m.get("classFqn")),
                        string(m.get("method"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.HttpPubSubPublishLinkRule> loadHttpPubSubPublishLinks(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.HttpPubSubPublishLinkRule(
                        string(m.get("configUriKey")),
                        string(m.get("configTopicKey")),
                        string(m.get("clientClass")),
                        string(m.get("callerPattern")),
                        string(m.get("flowStep")),
                        loadTopicAliases(m.get("topicAliases"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> loadTopicAliases(Object raw) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> aliases)) return map;
        aliases.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                map.put(String.valueOf(key), list.stream().map(String::valueOf).toList());
            }
        });
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.KafkaClassLinkRule> loadKafkaClassLinks(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.KafkaClassLinkRule(
                        string(m.get("module")),
                        string(m.get("topicShortId")),
                        string(m.get("role")),
                        string(m.get("classFqn")),
                        string(m.get("method"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.KafkaTopicAliasRule> loadKafkaTopicAliases(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.KafkaTopicAliasRule(
                        string(m.get("logical")),
                        stringList(m.get("aliases"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.DeclaredGateRule> loadDeclaredGates(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.DeclaredGateRule(
                        string(m.get("classFqn")),
                        string(m.get("flowStep")),
                        string(m.get("gateKind")),
                        string(m.get("gateKey")),
                        string(m.get("requiredValue")),
                        string(m.get("operator")),
                        string(m.get("effectWhenFail")),
                        string(m.get("testPrecondition")),
                        doubleOrDefault(m.get("confidence"), 0.90)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static MessagingRulePack.CrossRepoTraceRule loadCrossRepoTrace(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return MessagingRulePack.CrossRepoTraceRule.empty();
        }
        List<MessagingRulePack.TerminalTopicRule> terminalTopics = list(map.get("terminalTopics")).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.TerminalTopicRule(
                        string(m.get("id")),
                        string(m.get("pattern")),
                        string(m.get("boundary")),
                        string(m.get("note"))))
                .toList();
        return new MessagingRulePack.CrossRepoTraceRule(
                stringList(map.get("manifestOnlyRepos")),
                stringList(map.get("catalogOnlyRepos")),
                terminalTopics);
    }

    @SuppressWarnings("unchecked")
    private static List<MessagingRulePack.TerminalRetryPathRule> loadTerminalRetryPaths(Object raw) {
        return list(raw).stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new MessagingRulePack.TerminalRetryPathRule(
                        string(m.get("topicMatch")),
                        string(m.get("cronJobName")),
                        string(m.get("bqTableSuffix")),
                        string(m.get("partnerVariant")),
                        string(m.get("note"))))
                .toList();
    }

    private InputStream openStream(Resource resource) throws IOException {
        if (resource.exists()) {
            return resource.getInputStream();
        }
        String path = System.getenv("TESTSEER_RULE_PACK");
        if (path != null && !path.isBlank() && Files.isRegularFile(Path.of(path))) {
            return Files.newInputStream(Path.of(path));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List<?> list) return (List<Object>) list;
        return List.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double doubleOrDefault(Object value, double defaultVal) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return defaultVal;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultVal;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}

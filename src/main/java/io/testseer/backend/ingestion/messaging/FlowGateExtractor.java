package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FlowGateExtractor {

    private static final Pattern VALUE_FLAG =
            Pattern.compile("@Value\\(\"\\$\\{([^:}]+)(?::([^}]*))?\\}\"\\)");
    private static final Pattern SKIP_IF_FALSE =
            Pattern.compile("if\\s*\\(!\\s*(\\w+)\\s*\\)");
    private static final Pattern SYSTEM_CONFIG_FIND_BY_KEY =
            Pattern.compile("findByConfigKey\\(\"([^\"]+)\"\\)");
    private static final Pattern CONFIG_ENABLED_PARTNER =
            Pattern.compile("isConfigEnabled\\s*\\(\\s*[^,]+,\\s*SystemConfigKeys\\.([A-Za-z0-9_]+)\\.(?:name|toString)\\(\\)\\s*\\)");
    private static final Pattern CONFIG_ENABLED_GLOBAL =
            Pattern.compile("isConfigEnabled\\s*\\(\\s*SystemConfigKeys\\.([A-Za-z0-9_]+)\\.(?:name|toString)\\(\\)\\s*\\)");
    private static final Pattern CONFIG_BANNER_PARTNER =
            Pattern.compile("isBannerOrPartnerLevelSystemConfigEnabled\\s*\\([^,]+,\\s*[^,]+,\\s*SystemConfigKeys\\.([A-Za-z0-9_]+)\\.(?:name|toString)\\(\\)");
    private static final Pattern CONFIG_READ_PARTNER =
            Pattern.compile("config(?:OrDefault)?\\s*\\(\\s*[^,]+,\\s*SystemConfigKeys\\.([A-Za-z0-9_]+)\\.(?:name|toString)\\(\\)");
    private static final Pattern CONFIG_READ_GLOBAL =
            Pattern.compile("config(?:OrDefault)?\\s*\\(\\s*SystemConfigKeys\\.([A-Za-z0-9_]+)\\.(?:name|toString)\\(\\)");
    private static final Pattern CONDITIONAL_ON_PROPERTY_DIRECT =
            Pattern.compile("@ConditionalOnProperty\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern CONDITIONAL_ON_PROPERTY_NAMED =
            Pattern.compile("@ConditionalOnProperty\\s*\\([^)]*(?:name|value)\\s*=\\s*\"([^\"]+)\"");
    /** Read-side: .filter(... getIsPublished()) — non-greedy to survive nested parens */
    private static final Pattern BOOLEAN_GETTER_IN_FILTER =
            Pattern.compile("\\.filter\\([^;]+?\\.get(Is\\w+|Has\\w+)\\(\\)");
    /** Read-side: if (!publishStatusFilter.filter(offerPidMap)) */
    private static final Pattern FILTER_DELEGATE_GUARD =
            Pattern.compile("if\\s*\\(\\s*!\\s*(\\w+)\\.filter\\s*\\((\\w+)\\)\\s*\\)");
    /** preLive wiring via ActiveOfferFilter */
    private static final Pattern PRELIVE_ACTIVE_OFFER =
            Pattern.compile("ActiveOfferFilter\\.instance\\([^)]*FilterEnum\\.preLive");
    /** Write default — must not emit a read gate */
    private static final Pattern WRITE_DEFAULT_IS_PUBLISHED =
            Pattern.compile("getIsPublished\\(\\)\\s*==\\s*null");

    private final MessagingRulePack rulePack;

    public FlowGateExtractor(MessagingRulePackLoader rulePackLoader) {
        this.rulePack = rulePackLoader.getRulePack();
    }

    public List<FactBatch.FlowGateFact> extract(
            List<ParsedModel> models,
            List<ProtoSchemaExtractor.JavaSourceFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> yamlFiles) {

        List<FactBatch.FlowGateFact> gates = new ArrayList<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null) continue;
            gates.addAll(extractAnnotationGates(model));
        }

        Set<String> indexedFqns = new HashSet<>();
        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            indexedFqns.add(file.classFqn());
            gates.addAll(extractCodeGates(file));
        }

        gates.addAll(extractDeclaredGates(indexedFqns));
        gates.addAll(extractYamlGates(yamlFiles));
        return dedupeGates(gates);
    }

    private List<FactBatch.FlowGateFact> extractAnnotationGates(ParsedModel model) {
        List<FactBatch.FlowGateFact> gates = new ArrayList<>();
        for (String ann : model.annotations()) {
            if (!ann.contains("ConditionalOnProperty")) continue;
            Matcher direct = Pattern.compile("ConditionalOnProperty\\(\\{?\"([^\"]+)\"").matcher(ann);
            String key = direct.find() ? direct.group(1) : null;
            if (key == null) {
                Matcher named = Pattern.compile("(?:name|value)\\s*=\\s*\"([^\"]+)\"").matcher(ann);
                if (named.find()) {
                    key = named.group(1);
                }
            }
            if (key != null) {
                gates.add(gate(model.classFqn(), null, "CONDITIONAL_BEAN", key,
                        "true", "EQ", "NO_BEAN",
                        "Bean " + model.classFqn() + " requires property " + key + "=true",
                        "JAVA_ANNOTATION", null, 0.95));
            }
        }
        return gates;
    }

    private List<FactBatch.FlowGateFact> extractCodeGates(ProtoSchemaExtractor.JavaSourceFile file) {
        List<FactBatch.FlowGateFact> gates = new ArrayList<>();
        String fqn = file.classFqn();
        String content = file.content();

        gates.addAll(extractConditionalOnPropertyFromSource(fqn, content));

        Matcher vm = VALUE_FLAG.matcher(content);
        while (vm.find()) {
            String key = vm.group(1);
            String defaultVal = vm.group(2);
            gates.add(gate(fqn, flowStepFor(fqn), "CODE_FLAG", key,
                    defaultVal != null ? defaultVal : "true", "EQ", "NO_PUBLISH",
                    "Set " + key + "=true for publish path",
                    "JAVA_AST", null, 0.90));
        }

        Matcher sm = SKIP_IF_FALSE.matcher(content);
        while (sm.find()) {
            gates.add(gate(fqn, flowStepFor(fqn), "CODE_FLAG", sm.group(1) + "=true",
                    "true", "EQ", "SKIP",
                    "Flag " + sm.group(1) + " must be true",
                    "JAVA_AST", null, 0.85));
        }

        for (MessagingRulePack.CodeGateRule rule : rulePack.codeGateRules()) {
            if (rule.pattern() == null) continue;
            Pattern pattern = Pattern.compile(rule.pattern());
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String gateKey = rule.gateKey();
                if (rule.gateKeyFromGroup() != null) {
                    gateKey = matcher.group(rule.gateKeyFromGroup());
                }
                String required = rule.requiredValue();
                if (rule.requiredValueFromGroup() != null) {
                    required = matcher.group(rule.requiredValueFromGroup());
                }
                String precondition = rule.testPrecondition();
                if (precondition == null && rule.testPreconditionTemplate() != null && required != null) {
                    precondition = rule.testPreconditionTemplate().replace("{0}", required);
                }
                gates.add(gate(
                        fqn,
                        rule.flowStep() != null ? rule.flowStep() : flowStepFor(fqn),
                        rule.gateKind(),
                        gateKey,
                        required,
                        rule.operator(),
                        rule.effectWhenFail(),
                        precondition,
                        "JAVA_AST",
                        null,
                        rule.confidence()
                ));
            }
        }

        gates.addAll(extractSystemConfigGates(fqn, content));

        if (!WRITE_DEFAULT_IS_PUBLISHED.matcher(content).find()) {
            Matcher bgf = BOOLEAN_GETTER_IN_FILTER.matcher(content);
            while (bgf.find()) {
                String field = bgf.group(1);
                String gateKey = inferGateKeyFromContent(content, bgf.start(), field);
                gates.add(gate(fqn, flowStepFor(fqn), "BUSINESS_RULE", gateKey,
                        "true", "EQ", "SKIP",
                        gateKey + " must be true — rows failing this guard are excluded",
                        "JAVA_AST", null, 0.80));
            }
        }

        Matcher fdg = FILTER_DELEGATE_GUARD.matcher(content);
        while (fdg.find()) {
            String varName = fdg.group(2);
            String entity = capitalizeEntity(varName);
            gates.add(gate(fqn, flowStepFor(fqn), "BUSINESS_RULE", entity + ".publishFilter",
                    "pass", "EQ", "SKIP",
                    entity + " must pass " + fdg.group(1) + " before proceeding",
                    "JAVA_AST", null, 0.78));
        }

        if (PRELIVE_ACTIVE_OFFER.matcher(content).find()) {
            gates.add(gate(fqn, flowStepFor(fqn), "BUSINESS_RULE", "Offer.preLive",
                    "yes", "EQ", "SKIP",
                    "preLive=yes required to include not-yet-active offers",
                    "JAVA_AST", null, 0.75));
        }

        return gates;
    }

    private List<FactBatch.FlowGateFact> extractConditionalOnPropertyFromSource(String fqn, String content) {
        List<FactBatch.FlowGateFact> gates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher direct = CONDITIONAL_ON_PROPERTY_DIRECT.matcher(content);
        while (direct.find()) {
            String key = direct.group(1);
            if (seen.add(key)) {
                gates.add(gate(fqn, flowStepFor(fqn), "CONDITIONAL_BEAN", key,
                        "true", "EQ", "NO_BEAN",
                        "Bean " + fqn + " requires property " + key + "=true",
                        "JAVA_ANNOTATION", null, 0.95));
            }
        }
        Matcher named = CONDITIONAL_ON_PROPERTY_NAMED.matcher(content);
        while (named.find()) {
            String key = named.group(1);
            if (seen.add(key)) {
                gates.add(gate(fqn, flowStepFor(fqn), "CONDITIONAL_BEAN", key,
                        "true", "EQ", "NO_BEAN",
                        "Bean " + fqn + " requires property " + key + "=true",
                        "JAVA_ANNOTATION", null, 0.95));
            }
        }
        return gates;
    }

    private List<FactBatch.FlowGateFact> extractSystemConfigGates(String fqn, String content) {
        List<FactBatch.FlowGateFact> gates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher findByKey = SYSTEM_CONFIG_FIND_BY_KEY.matcher(content);
        while (findByKey.find()) {
            String key = findByKey.group(1);
            if (seen.add(key)) {
                gates.add(systemConfigGate(fqn, key,
                        "true", "EQ", "SKIP",
                        "system_configuration." + key + " must include partner",
                        0.90));
            }
        }

        extractSystemConfigKey(content, CONFIG_ENABLED_PARTNER, seen).forEach(key ->
                gates.add(systemConfigGate(fqn, key,
                        "true", "EQ", "SKIP",
                        "system_configuration." + key + " must be enabled for partner",
                        0.92)));

        extractSystemConfigKey(content, CONFIG_ENABLED_GLOBAL, seen).forEach(key ->
                gates.add(systemConfigGate(fqn, key,
                        "true", "EQ", "SKIP",
                        "system_configuration." + key + " must be enabled",
                        0.91)));

        extractSystemConfigKey(content, CONFIG_BANNER_PARTNER, seen).forEach(key ->
                gates.add(systemConfigGate(fqn, key,
                        "true", "EQ", "SKIP",
                        "system_configuration." + key + " must be enabled for partner/banner",
                        0.91)));

        extractSystemConfigKey(content, CONFIG_READ_PARTNER, seen).forEach(key ->
                gates.add(systemConfigGate(fqn, key,
                        "non-empty", "EXISTS", "SKIP",
                        "system_configuration." + key + " must be populated for partner",
                        0.88)));

        extractSystemConfigKey(content, CONFIG_READ_GLOBAL, seen).forEach(key ->
                gates.add(systemConfigGate(fqn, key,
                        "non-empty", "EXISTS", "SKIP",
                        "system_configuration." + key + " must be populated",
                        0.87)));

        return gates;
    }

    private static Set<String> extractSystemConfigKey(String content, Pattern pattern, Set<String> seen) {
        Set<String> keys = new HashSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (seen.add(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private FactBatch.FlowGateFact systemConfigGate(
            String fqn, String key, String required, String operator,
            String effect, String precondition, double confidence) {
        return gate(fqn, flowStepFor(fqn), "SYSTEM_CONFIG", key,
                required, operator, effect, precondition, "JAVA_AST", null, confidence);
    }

    private List<FactBatch.FlowGateFact> extractDeclaredGates(Set<String> indexedFqns) {
        List<FactBatch.FlowGateFact> gates = new ArrayList<>();
        for (MessagingRulePack.DeclaredGateRule rule : rulePack.declaredGates()) {
            if (rule.classFqn() == null || !indexedFqns.contains(rule.classFqn())) continue;
            gates.add(gate(
                    rule.classFqn(),
                    rule.flowStep() != null ? rule.flowStep() : flowStepFor(rule.classFqn()),
                    rule.gateKind(),
                    rule.gateKey(),
                    rule.requiredValue(),
                    rule.operator(),
                    rule.effectWhenFail(),
                    rule.testPrecondition(),
                    "RULE_PACK",
                    null,
                    rule.confidence()
            ));
        }
        return gates;
    }

    /** Best-effort entity prefix from filter lambda (e.g. pidOfferMap → OfferPidMap.IsPublished). */
    private static String inferGateKeyFromContent(String content, int filterStart, String field) {
        int windowStart = Math.max(0, filterStart - 120);
        String window = content.substring(windowStart, Math.min(content.length(), filterStart + 80));
        Matcher mapVar = Pattern.compile("(\\w*[Pp]id[Mm]ap\\w*)\\.get\\(").matcher(window);
        if (mapVar.find()) {
            return capitalizeEntity(mapVar.group(1)) + "." + field;
        }
        Matcher var = Pattern.compile("(\\w+)\\.get\\(" + Pattern.quote(field) + "\\)").matcher(window);
        if (var.find()) {
            return capitalizeEntity(var.group(1)) + "." + field;
        }
        return field;
    }

    private static String capitalizeEntity(String varName) {
        if (varName == null || varName.isBlank()) {
            return "Entity";
        }
        String base = varName;
        if (base.toLowerCase(Locale.ROOT).endsWith("map")) {
            base = base.substring(0, base.length() - 3) + "Map";
        }
        return base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
    }

    private List<FactBatch.FlowGateFact> extractYamlGates(List<YamlPubSubExtractor.ConfigFile> yamlFiles) {
        List<FactBatch.FlowGateFact> gates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YamlPubSubExtractor.ConfigFile file : yamlFiles) {
            if (!file.path().endsWith(".yaml") && !file.path().endsWith(".yml")) continue;
            EnvLaneResolver.EnvProfile env = EnvLaneResolver.resolve(file.path());
            String module = EnvLaneResolver.resolveModuleName(file.path());
            for (YamlConfigUtils.FlatMapSource source :
                    YamlConfigUtils.expandAndFlatten(file.path(), file.content())) {
                for (Map.Entry<String, String> entry : source.flat().entrySet()) {
                    String key = entry.getKey();
                    if (!key.toLowerCase(Locale.ROOT).endsWith("enabled")) continue;
                    String dedupeKey = key + "|" + source.sourcePath();
                    if (!seen.add(dedupeKey)) continue;
                    gates.add(gate(
                            module,
                            null, "YAML_FLAG", key, "true", "EQ", "NO_BEAN",
                            "Yaml flag " + key + " must be true in " + env.envLane(),
                            "YAML", source.sourcePath(), 0.85
                    ));
                }
            }
        }
        return gates;
    }

    private List<FactBatch.FlowGateFact> dedupeGates(List<FactBatch.FlowGateFact> gates) {
        Map<String, FactBatch.FlowGateFact> conditionalByKey = new LinkedHashMap<>();
        Map<String, String> yamlPathByKey = new LinkedHashMap<>();
        Set<String> systemConfigKeys = new HashSet<>();

        for (FactBatch.FlowGateFact gate : gates) {
            if ("SYSTEM_CONFIG".equals(gate.gateKind())) {
                systemConfigKeys.add(gate.guardedSymbolFqn() + "|" + gate.gateKey());
            }
            if ("CONDITIONAL_BEAN".equals(gate.gateKind())) {
                conditionalByKey.putIfAbsent(gate.gateKey(), gate);
            }
            if ("YAML_FLAG".equals(gate.gateKind()) && gate.yamlPath() != null) {
                yamlPathByKey.putIfAbsent(gate.gateKey(), gate.yamlPath());
            }
        }

        List<FactBatch.FlowGateFact> out = new ArrayList<>();
        for (FactBatch.FlowGateFact gate : gates) {
            if ("YAML_FLAG".equals(gate.gateKind()) && conditionalByKey.containsKey(gate.gateKey())) {
                continue;
            }
            if ("CODE_FLAG".equals(gate.gateKind())
                    && "isTrustedPartner=true".equals(gate.gateKey())
                    && systemConfigKeys.contains(gate.guardedSymbolFqn() + "|TrustedRedemptionEnabled")) {
                continue;
            }
            if ("CONDITIONAL_BEAN".equals(gate.gateKind()) && yamlPathByKey.containsKey(gate.gateKey())) {
                gate = new FactBatch.FlowGateFact(
                        gate.envLane(), gate.guardedSymbolFqn(), gate.guardedFlowStep(),
                        gate.guardedEdgeType(), gate.gateKind(), gate.gateKey(),
                        gate.requiredValue(), gate.requiredOperator(), gate.effectWhenFail(),
                        gate.skipLogPattern(), gate.testPrecondition(), gate.evidenceSource(),
                        yamlPathByKey.get(gate.gateKey()), gate.confidence());
            }
            out.add(gate);
        }
        return out;
    }

    private FactBatch.FlowGateFact gate(
            String symbol, String flowStep, String kind, String key,
            String required, String operator, String effect, String precondition,
            String source, String yamlPath, double confidence) {
        return new FactBatch.FlowGateFact(
                "unknown", symbol, flowStep, null, kind, key, required, operator,
                effect, null, precondition, source, yamlPath, confidence
        );
    }

    private String flowStepFor(String fqn) {
        if (fqn == null) return null;
        String lower = fqn.toLowerCase(Locale.ROOT);
        for (MessagingRulePack.ClassFlowStepRule rule : rulePack.classFlowStepRules()) {
            if (rule.match() != null && lower.contains(rule.match().toLowerCase(Locale.ROOT))) {
                return rule.flowStep();
            }
        }
        return null;
    }
}

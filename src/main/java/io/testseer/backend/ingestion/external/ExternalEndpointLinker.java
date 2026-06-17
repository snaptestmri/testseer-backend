package io.testseer.backend.ingestion.external;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import io.testseer.backend.ingestion.messaging.ValidationHintBuilder;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class ExternalEndpointLinker {

    private final MessagingRulePack rulePack;
    private final YamlExternalEndpointExtractor yamlExtractor;
    private final ExternalCallSiteExtractor callSiteExtractor;

    public ExternalEndpointLinker(
            MessagingRulePackLoader rulePackLoader,
            YamlExternalEndpointExtractor yamlExtractor,
            ExternalCallSiteExtractor callSiteExtractor) {
        this.rulePack = rulePackLoader.getRulePack();
        this.yamlExtractor = yamlExtractor;
        this.callSiteExtractor = callSiteExtractor;
    }

    public record LinkedExternalFacts(
            List<FactBatch.ExternalEndpointFact> endpoints,
            List<FactBatch.ExternalCallSiteFact> callSites,
            List<FactBatch.ValidationHintFact> hints
    ) {}

    public LinkedExternalFacts link(
            List<MessagingFactOrchestrator.SourceFile> javaFiles,
            List<ParsedModel> models,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            String defaultEnvLane) {

        List<YamlExternalEndpointExtractor.YamlEndpointCandidate> yamlCandidates =
                yamlExtractor.extract(configFiles);
        List<ExternalCallSiteExtractor.CallSiteCandidate> callSites =
                callSiteExtractor.extractCallSites(javaFiles);
        List<ExternalCallSiteExtractor.ConfigPropertiesBinding> configBindings =
                callSiteExtractor.extractConfigBindings(javaFiles);

        Map<String, String> prefixBySimpleClass = new LinkedHashMap<>();
        for (ExternalCallSiteExtractor.ConfigPropertiesBinding binding : configBindings) {
            prefixBySimpleClass.put(simpleName(binding.classFqn()), binding.prefix());
        }

        List<FactBatch.ExternalEndpointFact> endpoints = new ArrayList<>();
        List<FactBatch.ExternalCallSiteFact> callSiteFacts = new ArrayList<>();
        List<FactBatch.ValidationHintFact> hints = new ArrayList<>();

        for (MessagingRulePack.PartnerEndpointRule rule : rulePack.partnerEndpoints()) {
            String endpointId = rule.partner() + ":" + rule.operation().toLowerCase(Locale.ROOT);

            for (String configKey : rule.configKeys()) {
                for (YamlExternalEndpointExtractor.YamlEndpointCandidate yaml : yamlCandidates) {
                    if (!configKey.equals(yaml.configKey())) continue;

                    String callerFqn = findMatchingClassFqn(models, rule.match());
                    String clientFqn = findMatchingClassFqn(models, rule.clientClass());

                    endpoints.add(yamlExtractor.toFact(
                            yaml,
                            endpointId,
                            rule.partner(),
                            rule.operation(),
                            firstNonBlank(yaml.httpMethod(), rule.httpMethod()),
                            callerFqn,
                            clientFqn,
                            rule.flowStep(),
                            rule.authScheme(),
                            0.92
                    ));

                    for (ExternalCallSiteExtractor.CallSiteCandidate site : callSites) {
                        if (!matchesRuleClass(site.sourceSymbol(), rule)) continue;
                        if (site.configProperty() != null
                                && configKey.endsWith("." + kebabKey(site.configProperty()))) {
                            callSiteFacts.add(new FactBatch.ExternalCallSiteFact(
                                    site.sourceSymbol(),
                                    site.configAccessor(),
                                    resolvePrefix(configKey, site, prefixBySimpleClass),
                                    site.configProperty(),
                                    site.httpClientType(),
                                    site.httpClientMethod(),
                                    site.httpMethod() != null ? site.httpMethod() : rule.httpMethod(),
                                    endpointId,
                                    "javaparser+yaml",
                                    0.92
                            ));
                        } else if (site.httpClientMethod() != null
                                && ("exchange".equals(site.httpClientMethod())
                                || "callWithRetry".equals(site.httpClientMethod()))
                                && matchesClientClass(site.sourceSymbol(), rule.clientClass(), models)) {
                            callSiteFacts.add(new FactBatch.ExternalCallSiteFact(
                                    site.sourceSymbol(),
                                    site.configAccessor(),
                                    resolvePrefix(configKey, site, prefixBySimpleClass),
                                    site.configProperty(),
                                    site.httpClientType(),
                                    site.httpClientMethod(),
                                    site.httpMethod() != null ? site.httpMethod() : rule.httpMethod(),
                                    endpointId,
                                    "javaparser+yaml",
                                    0.85
                            ));
                        }
                    }

                    appendHints(hints, endpointId, rule.flowStep(), callerFqn, yaml.envLane());
                }
            }

            // Rule without config keys: still emit call-site facts for matching client classes
            if (rule.configKeys().isEmpty()) {
                for (ExternalCallSiteExtractor.CallSiteCandidate site : callSites) {
                    if (matchesClientClass(site.sourceSymbol(), rule.clientClass(), models)) {
                        callSiteFacts.add(new FactBatch.ExternalCallSiteFact(
                                site.sourceSymbol(),
                                site.configAccessor(),
                                site.configPrefix(),
                                site.configProperty(),
                                site.httpClientType(),
                                site.httpClientMethod(),
                                site.httpMethod() != null ? site.httpMethod() : rule.httpMethod(),
                                endpointId,
                                "javaparser",
                                0.75
                        ));
                    }
                }
            }
        }

        // Unlinked YAML URL facts (no rule pack match) — still indexed with lower confidence
        for (YamlExternalEndpointExtractor.YamlEndpointCandidate yaml : yamlCandidates) {
            boolean linked = endpoints.stream()
                    .anyMatch(e -> yaml.configKey().equals(e.configKey())
                            && yaml.envLane().equals(e.envLane()));
            if (linked) continue;

            String endpointId = "unlinked:" + sanitizeId(yaml.configKey());
            endpoints.add(yamlExtractor.toFact(
                    yaml,
                    endpointId,
                    inferPartner(yaml.configKey()),
                    "UNKNOWN",
                    inferHttpMethod(yaml.configKey()),
                    null,
                    null,
                    null,
                    null,
                    0.70
            ));
        }

        synthesizeFromOutboundPaths(rulePack.partnerEndpoints(), models, yamlCandidates, endpoints, defaultEnvLane);

        return new LinkedExternalFacts(endpoints, dedupeCallSites(callSiteFacts), hints);
    }

    private void synthesizeFromOutboundPaths(
            List<MessagingRulePack.PartnerEndpointRule> rules,
            List<ParsedModel> models,
            List<YamlExternalEndpointExtractor.YamlEndpointCandidate> yamlCandidates,
            List<FactBatch.ExternalEndpointFact> endpoints,
            String defaultEnvLane) {
        for (MessagingRulePack.PartnerEndpointRule rule : rules) {
            if (rule.configKeys().isEmpty()) continue;

            String clientFqn = findMatchingClassFqn(models, rule.clientClass());
            if (clientFqn == null) {
                clientFqn = findMatchingClassFqn(models, rule.match());
            }
            if (clientFqn == null) continue;

            String resolvedPath = resolveOutboundPath(models, clientFqn, rule);
            if (resolvedPath == null || resolvedPath.isBlank()) continue;

            for (String configKey : rule.configKeys()) {
                boolean linked = endpoints.stream()
                        .anyMatch(e -> configKey.equals(e.configKey()));
                if (linked) continue;

                String envLane = yamlCandidates.stream()
                        .filter(y -> configKey.equals(y.configKey()))
                        .map(YamlExternalEndpointExtractor.YamlEndpointCandidate::envLane)
                        .findFirst()
                        .orElse(defaultEnvLane);

                String endpointId = rule.partner() + ":" + rule.operation().toLowerCase(Locale.ROOT);
                endpoints.add(new FactBatch.ExternalEndpointFact(
                        endpointId,
                        rule.partner(),
                        rule.operation(),
                        rule.httpMethod(),
                        resolvedPath,
                        resolvedPath,
                        envLane,
                        rule.boundary(),
                        configKey,
                        null,
                        findMatchingClassFqn(models, rule.match()),
                        clientFqn,
                        rule.flowStep(),
                        rule.authScheme(),
                        "OUTBOUND_PATH",
                        0.78,
                        "{\"source\":\"outbound-path-synthesis\"}"
                ));
            }
        }
    }

    private static String resolveOutboundPath(
            List<ParsedModel> models,
            String clientFqn,
            MessagingRulePack.PartnerEndpointRule rule) {
        for (ParsedModel model : models) {
            if (model.classFqn() == null) continue;
            if (!model.classFqn().equals(clientFqn)
                    && !model.classFqn().contains(rule.clientClass())
                    && !model.classFqn().contains(rule.match())) {
                continue;
            }
            for (ParsedModel.OutboundCallDef call : model.outboundCalls()) {
                if (call.path() != null && !call.path().isBlank()) {
                    return call.path();
                }
            }
        }
        return null;
    }

    private void appendHints(
            List<FactBatch.ValidationHintFact> hints,
            String endpointId,
            String flowStep,
            String callerFqn,
            String envLane) {
        MessagingRulePack.ExternalEndpointHintRule hintRule =
                rulePack.externalEndpointHints().get(endpointId);
        if (hintRule == null) return;
        for (String hint : hintRule.hints()) {
            hints.add(new FactBatch.ValidationHintFact(
                    flowStep != null ? flowStep : "EXTERNAL_HTTP",
                    "EXTERNAL_ENDPOINT",
                    hint,
                    callerFqn,
                    envLane
            ));
        }
    }

    private List<FactBatch.ExternalCallSiteFact> dedupeCallSites(List<FactBatch.ExternalCallSiteFact> facts) {
        Map<String, FactBatch.ExternalCallSiteFact> deduped = new LinkedHashMap<>();
        for (FactBatch.ExternalCallSiteFact f : facts) {
            deduped.put(f.sourceSymbol() + "|" + f.httpClientMethod() + "|" + f.configProperty(), f);
        }
        return new ArrayList<>(deduped.values());
    }

    private static boolean matchesRuleClass(String sourceSymbol, MessagingRulePack.PartnerEndpointRule rule) {
        if (sourceSymbol == null) return false;
        return sourceSymbol.contains(rule.match()) || sourceSymbol.contains(rule.clientClass());
    }

    private static boolean matchesClientClass(
            String sourceSymbol, String clientClass, List<ParsedModel> models) {
        if (sourceSymbol == null || clientClass == null) return false;
        String classPart = sourceSymbol.contains("#")
                ? sourceSymbol.substring(0, sourceSymbol.indexOf('#'))
                : sourceSymbol;
        return classPart.endsWith("." + clientClass) || classPart.contains(clientClass);
    }

    private static String findMatchingClassFqn(List<ParsedModel> models, String simpleMatch) {
        if (simpleMatch == null) return null;
        return models.stream()
                .filter(m -> m.classFqn() != null && m.classFqn().contains(simpleMatch))
                .map(ParsedModel::classFqn)
                .findFirst()
                .orElse(null);
    }

    private static String resolvePrefix(
            String configKey,
            ExternalCallSiteExtractor.CallSiteCandidate site,
            Map<String, String> prefixBySimpleClass) {
        if (site.configPrefix() != null) return site.configPrefix();
        int lastDot = configKey.lastIndexOf('.');
        return lastDot > 0 ? configKey.substring(0, lastDot) : configKey;
    }

    private static String kebabKey(String camelProperty) {
        if (camelProperty == null) return "";
        return camelProperty.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String sanitizeId(String key) {
        return key.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase(Locale.ROOT);
    }

    private static String inferPartner(String configKey) {
        if (configKey == null) return "unknown";
        if (configKey.contains(".hyvee.")) return "hyvee";
        if (configKey.contains(".dg.")) return "dg";
        if (configKey.contains("ois-rest")) return "quotient";
        return "unknown";
    }

    private static String inferHttpMethod(String configKey) {
        if (configKey != null && configKey.contains("publish-details")) return "PUT";
        if (configKey != null && configKey.contains("login")) return "POST";
        if (configKey != null && configKey.contains("pubsub")) return "POST";
        return "POST";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}

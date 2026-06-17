package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class InboundRestTriggerExtractor {

    private static final String DIRECTION_INBOUND = "INBOUND";
    private static final String KIND_REST = "REST_INBOUND";
    private static final String KIND_WEBHOOK = "WEBHOOK_INBOUND";

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models, TriggerRulePack rulePack, String defaultEnvLane) {

        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null || model.endpoints().isEmpty()) continue;
            if (isFeignClient(model)) continue;

            for (ParsedModel.EndpointDef ep : model.endpoints()) {
                String handlerFqn = model.classFqn() + "#" + ep.methodName();
                String normalizedPath = normalizePath(ep.path());
                TriggerRulePack.InboundRestTriggerRule rule =
                        matchRule(rulePack, model.classFqn(), ep.methodName(), normalizedPath);

                String triggerKind = rule != null && rule.triggerKind() != null
                        ? rule.triggerKind()
                        : inferKind(model.classFqn(), normalizedPath);
                String actor = rule != null && rule.actor() != null
                        ? rule.actor()
                        : "unknown";
                String boundary = rule != null && rule.boundary() != null
                        ? rule.boundary()
                        : "EXTERNAL";
                String flowStep = rule != null ? rule.flowStep() : null;
                String envLane = rule != null && rule.envLane() != null
                        ? rule.envLane()
                        : (defaultEnvLane != null ? defaultEnvLane : "unknown");

                String triggerId = buildTriggerId(actor, triggerKind, ep.httpMethod(), normalizedPath, handlerFqn);
                String key = triggerId + "|" + envLane + "|" + ep.httpMethod() + "|" + normalizedPath;
                if (!seen.add(key)) continue;

                double confidence = rule != null ? 0.92 : heuristicConfidence(model.classFqn(), normalizedPath);

                results.add(new FactBatch.EntryTriggerFact(
                        triggerId,
                        triggerKind,
                        DIRECTION_INBOUND,
                        envLane,
                        actor,
                        boundary,
                        ep.httpMethod(),
                        normalizedPath,
                        model.classFqn(),
                        ep.methodName(),
                        flowStep,
                        model.filePath(),
                        rule != null ? "RULE_PACK" : "JAVA_HEURISTIC",
                        confidence,
                        attributes(model.classFqn(), handlerFqn)
                ));
            }
        }
        return results;
    }

    private static TriggerRulePack.InboundRestTriggerRule matchRule(
            TriggerRulePack rulePack,
            String classFqn,
            String methodName,
            String path) {

        TriggerRulePack.InboundRestTriggerRule pathMatch = null;
        for (TriggerRulePack.InboundRestTriggerRule rule : rulePack.inboundRestTriggers()) {
            if (rule.pathPrefix() != null && !rule.pathPrefix().isBlank()) {
                if (path != null && path.startsWith(rule.pathPrefix())) {
                    if (pathMatch == null || rule.pathPrefix().length() > pathMatch.pathPrefix().length()) {
                        pathMatch = rule;
                    }
                }
                continue;
            }
            if (rule.match() == null || rule.match().isBlank()) continue;
            if (matchesSymbol(rule.match(), classFqn, methodName)) {
                return rule;
            }
        }
        return pathMatch;
    }

    private static boolean matchesSymbol(String match, String classFqn, String methodName) {
        if (match.contains("#")) {
            return (classFqn + "#" + methodName).equals(match)
                    || (classFqn + "#" + methodName).endsWith("." + match);
        }
        String simple = simpleName(classFqn);
        return classFqn.contains(match) || simple.equals(match) || simple.contains(match);
    }

    private static String inferKind(String classFqn, String path) {
        String lowerClass = classFqn.toLowerCase(Locale.ROOT);
        String lowerPath = path != null ? path.toLowerCase(Locale.ROOT) : "";
        if (lowerClass.contains("webhook")
                || lowerPath.contains("/webhook")
                || lowerPath.contains("/callback")
                || lowerPath.contains("/status/update")) {
            return KIND_WEBHOOK;
        }
        return KIND_REST;
    }

    private static double heuristicConfidence(String classFqn, String path) {
        double c = 0.80;
        if (inferKind(classFqn, path).equals(KIND_WEBHOOK)) c = 0.88;
        return c;
    }

    private static String buildTriggerId(
            String actor, String kind, String httpMethod, String path, String handlerFqn) {
        if (path != null && !path.isBlank()) {
            return sanitize(actor) + ":" + sanitize(httpMethod) + ":" + sanitize(path);
        }
        return sanitize(actor) + ":" + sanitize(kind) + ":" + sanitize(simpleName(handlerFqn));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private static boolean isFeignClient(ParsedModel model) {
        return model.annotations().stream().anyMatch(a -> a.contains("FeignClient"));
    }

    private String attributes(String classFqn, String handlerFqn) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "classFqn", classFqn,
                    "handlerSymbol", handlerFqn
            ));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._/-]+", "-").toLowerCase(Locale.ROOT);
    }
}

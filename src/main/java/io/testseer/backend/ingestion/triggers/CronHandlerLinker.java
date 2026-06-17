package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.config.TriggerRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KFK-05: links {@code CRON_K8S} manifest triggers to Spring Boot launcher classes in monorepos
 * (e.g. {@code stc-retry-job} CronJob → {@code StcRetryJobApplication.main}).
 */
@Component
public class CronHandlerLinker {

    private static final Pattern JOB_MODULE_PATH = Pattern.compile("([^/]+-job)/src/");

    private final ObjectMapper mapper = new ObjectMapper();
    private final TriggerRulePackLoader rulePackLoader;

    public CronHandlerLinker(TriggerRulePackLoader rulePackLoader) {
        this.rulePackLoader = rulePackLoader;
    }

    public List<FactBatch.EntryTriggerFact> link(
            List<FactBatch.EntryTriggerFact> triggers,
            List<ParsedModel> models,
            Map<String, String> contentByPath) {
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        Map<String, LauncherCandidate> launchers = indexLaunchers(models, contentByPath);

        List<FactBatch.EntryTriggerFact> linked = new ArrayList<>();
        for (FactBatch.EntryTriggerFact trigger : triggers) {
            if (!"CRON_K8S".equals(trigger.triggerKind()) || trigger.linkedHandlerFqn() != null) {
                linked.add(trigger);
                continue;
            }
            String cronJobName = cronJobName(trigger);
            Optional<LauncherCandidate> rulePackMatch = matchRulePack(cronJobName);
            if (rulePackMatch.isPresent()) {
                linked.add(withHandler(trigger, rulePackMatch.get()));
                continue;
            }
            findLauncher(cronJobName, launchers)
                    .map(launcher -> withHandler(trigger, launcher))
                    .ifPresentOrElse(linked::add, () -> linked.add(trigger));
        }
        return linked;
    }

    private Map<String, LauncherCandidate> indexLaunchers(
            List<ParsedModel> models, Map<String, String> contentByPath) {
        Map<String, LauncherCandidate> byModule = new LinkedHashMap<>();
        if (models == null) {
            return byModule;
        }
        for (ParsedModel model : models) {
            if (model.classFqn() == null || model.filePath() == null) {
                continue;
            }
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (content == null || !content.contains("SpringApplication.run")) {
                continue;
            }
            String moduleKey = moduleKeyFromPath(model.filePath());
            if (moduleKey == null) {
                continue;
            }
            String method = content.contains("public static void main(") ? "main" : "run";
            LauncherCandidate candidate = new LauncherCandidate(model.classFqn(), method, model.filePath());
            byModule.putIfAbsent(moduleKey, candidate);
            byModule.putIfAbsent(normalizeJobName(moduleKey), candidate);
        }
        return byModule;
    }

    private Optional<LauncherCandidate> matchRulePack(String cronJobName) {
        if (cronJobName == null || cronJobName.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeJobName(cronJobName);
        for (TriggerRulePack.CronHandlerLinkRule rule : rulePackLoader.getRulePack().cronHandlerLinks()) {
            if (normalized.equals(normalizeJobName(rule.cronJobName()))) {
                String method = rule.method() != null && !rule.method().isBlank() ? rule.method() : "main";
                return Optional.of(new LauncherCandidate(rule.classFqn(), method, "rule-pack"));
            }
        }
        return Optional.empty();
    }

    private Optional<LauncherCandidate> findLauncher(
            String cronJobName, Map<String, LauncherCandidate> launchers) {
        if (cronJobName == null || cronJobName.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeJobName(cronJobName);
        if (launchers.containsKey(normalized)) {
            return Optional.of(launchers.get(normalized));
        }
        return launchers.entrySet().stream()
                .filter(e -> normalized.contains(e.getKey()) || e.getKey().contains(normalized))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static String moduleKeyFromPath(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int jobsIdx = normalized.indexOf("evaluation-jobs/");
        if (jobsIdx >= 0) {
            String tail = normalized.substring(jobsIdx + "evaluation-jobs/".length());
            int slash = tail.indexOf('/');
            return slash > 0 ? tail.substring(0, slash) : tail;
        }
        Matcher jobModule = JOB_MODULE_PATH.matcher(normalized);
        if (jobModule.find()) {
            return jobModule.group(1);
        }
        return moduleKeyFromLauncherClass(normalized);
    }

    private static String moduleKeyFromLauncherClass(String filePath) {
        int slash = filePath.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String file = filePath.substring(slash + 1);
        if (!file.endsWith("Application.java")) {
            return null;
        }
        String className = file.substring(0, file.length() - ".java".length());
        if (className.endsWith("JobApplication")) {
            className = className.substring(0, className.length() - "JobApplication".length());
        } else if (className.endsWith("Application")) {
            className = className.substring(0, className.length() - "Application".length());
        } else {
            return null;
        }
        return camelCaseToKebab(className);
    }

    private static String camelCaseToKebab(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.startsWith("Platform") && value.length() > "Platform".length()
                ? value.substring("Platform".length())
                : value;
        return trimmed
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeJobName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace("_", "-")
                .replaceAll("-batch-job$", "-job")
                .replaceAll("-job$", "");
    }

    private String cronJobName(FactBatch.EntryTriggerFact trigger) {
        if (trigger.triggerId() != null && trigger.triggerId().startsWith("k8s-cron:")) {
            return trigger.triggerId().substring("k8s-cron:".length());
        }
        if (trigger.attributes() == null || trigger.attributes().isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(trigger.attributes());
            if (node.hasNonNull("cronJob")) {
                return node.get("cronJob").asText();
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return null;
    }

    private FactBatch.EntryTriggerFact withHandler(
            FactBatch.EntryTriggerFact trigger, LauncherCandidate launcher) {
        return new FactBatch.EntryTriggerFact(
                trigger.triggerId(),
                trigger.triggerKind(),
                trigger.direction(),
                trigger.envLane(),
                trigger.actor(),
                trigger.boundary(),
                trigger.httpMethod(),
                trigger.pathPattern(),
                launcher.classFqn(),
                launcher.method(),
                trigger.flowStep(),
                trigger.sourceRef(),
                "K8S_MANIFEST+JAVA_LAUNCHER",
                Math.max(trigger.confidence(), 0.93),
                mergeAttributes(trigger.attributes(), launcher.classFqn(), launcher.method())
        );
    }

    private String mergeAttributes(String existing, String classFqn, String method) {
        try {
            Map<String, Object> attrs = existing != null && !existing.isBlank()
                    ? mapper.readValue(existing, Map.class)
                    : new LinkedHashMap<>();
            attrs.put("linkedLauncherClass", classFqn);
            attrs.put("linkedLauncherMethod", method);
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return existing != null ? existing : "{}";
        }
    }

    private record LauncherCandidate(String classFqn, String method, String filePath) {}
}

package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enriches {@code SPRING_BOOT_MAIN} triggers with K8s Deployment manifest evidence (BL-054). */
@Component
public class SpringBootMainDeploymentLinker {

    private static final Pattern DEPLOYMENT = Pattern.compile(
            "kind:\\s*Deployment[\\s\\S]*?metadata:\\s*[\\s\\S]*?name:\\s*([\\w-]+)",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> link(
            List<FactBatch.EntryTriggerFact> triggers,
            List<YamlPubSubExtractor.ConfigFile> configFiles) {
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        Map<String, String> deploymentByModule = indexDeployments(configFiles);

        List<FactBatch.EntryTriggerFact> linked = new ArrayList<>();
        for (FactBatch.EntryTriggerFact trigger : triggers) {
            if (!"SPRING_BOOT_MAIN".equals(trigger.triggerKind())) {
                linked.add(trigger);
                continue;
            }
            String moduleDir = moduleDirFromAttributes(trigger.attributes());
            String deploymentName = findDeployment(moduleDir, deploymentByModule);
            if (deploymentName == null) {
                linked.add(trigger);
                continue;
            }
            linked.add(enrich(trigger, deploymentName));
        }
        return linked;
    }

    private Map<String, String> indexDeployments(List<YamlPubSubExtractor.ConfigFile> configFiles) {
        Map<String, String> byModule = new LinkedHashMap<>();
        if (configFiles == null) {
            return byModule;
        }
        for (YamlPubSubExtractor.ConfigFile file : configFiles) {
            if (file.content() == null) {
                continue;
            }
            String lower = file.path().toLowerCase(Locale.ROOT);
            if (!lower.contains("deployment") && !file.content().contains("kind: Deployment")) {
                continue;
            }
            Matcher m = DEPLOYMENT.matcher(file.content());
            while (m.find()) {
                String name = m.group(1);
                byModule.putIfAbsent(name, name);
                byModule.putIfAbsent(normalize(name), name);
            }
        }
        return byModule;
    }

    private static String findDeployment(String moduleDir, Map<String, String> deploymentByModule) {
        if (moduleDir == null || deploymentByModule.isEmpty()) {
            return null;
        }
        if (deploymentByModule.containsKey(moduleDir)) {
            return deploymentByModule.get(moduleDir);
        }
        String normalized = normalize(moduleDir);
        if (deploymentByModule.containsKey(normalized)) {
            return deploymentByModule.get(normalized);
        }
        return deploymentByModule.entrySet().stream()
                .filter(e -> normalized.contains(e.getKey()) || e.getKey().contains(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private FactBatch.EntryTriggerFact enrich(FactBatch.EntryTriggerFact trigger, String deploymentName) {
        return new FactBatch.EntryTriggerFact(
                trigger.triggerId(),
                trigger.triggerKind(),
                trigger.direction(),
                trigger.envLane(),
                trigger.actor(),
                trigger.boundary(),
                trigger.httpMethod(),
                "/deploy/" + deploymentName,
                trigger.linkedHandlerFqn(),
                trigger.linkedMethod(),
                trigger.flowStep(),
                trigger.sourceRef(),
                trigger.evidenceSource() + "+K8S_DEPLOYMENT",
                Math.max(trigger.confidence(), 0.93),
                mergeAttributes(trigger.attributes(), deploymentName)
        );
    }

    private String moduleDirFromAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(attributesJson);
            if (node.hasNonNull("moduleDir")) {
                return node.get("moduleDir").asText();
            }
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return null;
    }

    private String mergeAttributes(String existing, String deploymentName) {
        try {
            Map<String, Object> attrs = existing != null && !existing.isBlank()
                    ? mapper.readValue(existing, Map.class)
                    : new LinkedHashMap<>();
            attrs.put("deploymentName", deploymentName);
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return existing != null ? existing : "{}";
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

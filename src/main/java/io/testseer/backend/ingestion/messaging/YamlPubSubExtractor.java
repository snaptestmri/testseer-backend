package io.testseer.backend.ingestion.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YamlPubSubExtractor {

    private static final Pattern TOPIC_KEY =
            Pattern.compile("(?:^|\\.)pubsub\\.publisher\\.topicId\\.([\\w-]+)$");
    private static final Pattern SUBSCRIPTION_KEY =
            Pattern.compile("(?:subscription-id|subscriptions\\.id|subscription\\.id)$");
    private static final Pattern FULL_TOPIC =
            Pattern.compile("^projects/([^/]+)/topics/(.+)$");
    private static final Pattern FULL_SUB =
            Pattern.compile("^projects/([^/]+)/subscriptions/(.+)$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Yaml yaml = new Yaml();

    public List<FactBatch.PubSubResourceFact> extract(List<ConfigFile> configFiles) {
        List<FactBatch.PubSubResourceFact> results = new ArrayList<>();
        Map<String, String> projectByPath = new LinkedHashMap<>();

        for (ConfigFile file : configFiles) {
            if (!isYaml(file.path())) continue;
            EnvLaneResolver.EnvProfile env = EnvLaneResolver.resolve(file.path());
            String module = EnvLaneResolver.resolveModuleName(file.path());
            Map<String, Object> root = parseYaml(file.content());
            if (root == null) continue;

            Map<String, String> flat = flatten(root, "");
            String gcpProject = flat.get("metrics.projectId");
            if (gcpProject != null) {
                projectByPath.put(file.path(), gcpProject);
            }

            for (Map.Entry<String, String> e : flat.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (value == null || value.isBlank()) continue;

                Matcher topicMatcher = TOPIC_KEY.matcher(key);
                if (topicMatcher.find()) {
                    results.add(buildTopicFact(file.path(), env, module, key, value, gcpProject));
                    continue;
                }

                if (SUBSCRIPTION_KEY.matcher(key).find() || key.contains("subscription")) {
                    if (key.contains("topicId") || key.endsWith(".enabled")) continue;
                    if (looksLikeSubscriptionId(value)) {
                        results.add(buildSubFact(file.path(), env, module, key, value, gcpProject));
                    }
                }
            }
        }

        enrichProjects(results, projectByPath);
        return results;
    }

    private FactBatch.PubSubResourceFact buildTopicFact(
            String yamlPath, EnvLaneResolver.EnvProfile env, String module,
            String springKey, String value, String gcpProject) {
        String shortId = value;
        String project = gcpProject;
        Matcher full = FULL_TOPIC.matcher(value);
        if (full.matches()) {
            project = full.group(1);
            shortId = full.group(2);
        }
        return new FactBatch.PubSubResourceFact(
                "TOPIC", shortId, env.envLane(), env.envProfile(), project,
                fullResourceId(project, "topics", shortId),
                "PUBLISH", springKey, yamlPath, module,
                null, null, EnvLaneResolver.resolveWorkloadName(module),
                "YAML", full.matches() ? 1.0 : 0.85, attributes(springKey, value)
        );
    }

    private FactBatch.PubSubResourceFact buildSubFact(
            String yamlPath, EnvLaneResolver.EnvProfile env, String module,
            String springKey, String value, String gcpProject) {
        String shortId = value;
        String project = gcpProject;
        Matcher full = FULL_SUB.matcher(value);
        if (full.matches()) {
            project = full.group(1);
            shortId = full.group(2);
        }
        return new FactBatch.PubSubResourceFact(
                "SUBSCRIPTION", shortId, env.envLane(), env.envProfile(), project,
                fullResourceId(project, "subscriptions", shortId),
                "SUBSCRIBE", springKey, yamlPath, module,
                null, null, EnvLaneResolver.resolveWorkloadName(module),
                "YAML", full.matches() ? 1.0 : 0.85, attributes(springKey, value)
        );
    }

    private void enrichProjects(List<FactBatch.PubSubResourceFact> facts, Map<String, String> projects) {
        for (int i = 0; i < facts.size(); i++) {
            FactBatch.PubSubResourceFact f = facts.get(i);
            if (f.gcpProject() != null) continue;
            String project = projects.get(f.yamlPath());
            if (project == null) continue;
            facts.set(i, new FactBatch.PubSubResourceFact(
                    f.resourceKind(), f.shortId(), f.envLane(), f.envProfile(), project,
                    fullResourceId(project,
                            "TOPIC".equals(f.resourceKind()) ? "topics" : "subscriptions", f.shortId()),
                    f.role(), f.springKey(), f.yamlPath(), f.moduleName(),
                    f.linkedClassFqn(), f.linkedMethod(), f.workloadName(),
                    f.evidenceSource(), f.confidence(), f.attributes()
            ));
        }
    }

    private boolean looksLikeSubscriptionId(String value) {
        return value.contains("_S.") || value.startsWith("S.") || value.contains(".S.");
    }

    private String fullResourceId(String project, String kind, String shortId) {
        if (project == null || shortId == null) return null;
        return "projects/" + project + "/" + kind + "/" + shortId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String content) {
        try {
            Object parsed = yaml.load(content);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
            // skip malformed yaml
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> flatten(Map<String, Object> map, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object val = e.getValue();
            if (val instanceof Map<?, ?> nested) {
                out.putAll(flatten((Map<String, Object>) nested, key));
            } else if (val != null) {
                out.put(key, String.valueOf(val));
            }
        }
        return out;
    }

    private String attributes(String springKey, String value) {
        try {
            return mapper.writeValueAsString(Map.of("springKey", springKey, "rawValue", value));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private boolean isYaml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    public record ConfigFile(String path, String content) {}
}

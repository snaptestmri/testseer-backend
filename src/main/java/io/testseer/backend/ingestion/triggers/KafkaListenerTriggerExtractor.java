package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.YamlConfigUtils;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KafkaListenerTriggerExtractor {

    private static final Pattern KAFKA_LISTENER =
            Pattern.compile("@KafkaListener\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern TOPICS_ATTR =
            Pattern.compile("topics\\s*=\\s*\\{?\\s*\"([^\"]+)\"");
    private static final Pattern TOPICS_PROPERTY =
            Pattern.compile("topics\\s*=\\s*\"\\$\\{([^}]+)}\"");
    private static final Pattern GROUP_ID =
            Pattern.compile("groupId\\s*=\\s*\"\\$?\\{?([^}\"]+)}?\"");
    private static final Pattern METHOD =
            Pattern.compile("(public|protected)\\s+[\\w<>,\\[\\].\\s]+\\s+(\\w+)\\s*\\(");
    private static final Pattern CONDITIONAL_PROPERTY =
            Pattern.compile("@ConditionalOnProperty\\s*\\(\\s*\"([^\"]+)\"");

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models,
            Map<String, String> contentByPath,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            String defaultEnvLane) {

        Map<String, String> yamlFlat = buildYamlFlat(configFiles);
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null) continue;
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (content == null || !content.contains("@KafkaListener")) continue;

            String enabledProperty = extractConditionalProperty(content);
            Matcher listenerMatcher = KAFKA_LISTENER.matcher(content);
            while (listenerMatcher.find()) {
                String annoBody = listenerMatcher.group(1);
                String topic = resolveTopic(annoBody, yamlFlat);
                if (topic == null || topic.isBlank()) continue;

                String method = enclosingMethod(content, listenerMatcher.start());
                String triggerId = "kafka:" + sanitize(topic) + ":" + sanitize(model.classFqn());
                if (!seen.add(triggerId)) continue;

                String groupId = extractGroupId(annoBody, yamlFlat);
                results.add(new FactBatch.EntryTriggerFact(
                        triggerId,
                        "KAFKA_SUBSCRIBE",
                        "INBOUND",
                        defaultEnvLane != null ? defaultEnvLane : "unknown",
                        "kafka",
                        "INTERNAL",
                        null,
                        topic,
                        model.classFqn(),
                        method,
                        null,
                        model.filePath(),
                        "JAVA_ANNOTATION",
                        topic.contains("${") ? 0.88 : 0.95,
                        attributes(topic, method, groupId, enabledProperty)
                ));
            }
        }
        return results;
    }

    private static Map<String, String> buildYamlFlat(List<YamlPubSubExtractor.ConfigFile> configFiles) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (configFiles == null) return merged;
        for (YamlPubSubExtractor.ConfigFile file : configFiles) {
            merged.putAll(YamlConfigUtils.mergeFlatMaps(
                    YamlConfigUtils.expandAndFlatten(file.path(), file.content())));
        }
        return merged;
    }

    private static String resolveTopic(String annoBody, Map<String, String> yamlFlat) {
        Matcher literal = TOPICS_ATTR.matcher(annoBody);
        if (literal.find()) {
            String raw = literal.group(1);
            if (raw.startsWith("${") && raw.endsWith("}")) {
                String key = raw.substring(2, raw.length() - 1);
                String resolved = yamlFlat.get(key);
                if (resolved != null) {
                    return resolved;
                }
                return YamlConfigUtils.resolveProperty(raw, yamlFlat);
            }
            return raw;
        }
        Matcher property = TOPICS_PROPERTY.matcher(annoBody);
        if (property.find()) {
            String key = property.group(1).trim();
            String resolved = yamlFlat.get(key);
            if (resolved != null) {
                return resolved;
            }
            return YamlConfigUtils.resolveProperty("${" + key + "}", yamlFlat);
        }
        return null;
    }

    private static String extractGroupId(String annoBody, Map<String, String> yamlFlat) {
        Matcher group = GROUP_ID.matcher(annoBody);
        if (!group.find()) return null;
        String raw = group.group(1).trim();
        if (raw.startsWith("${") && raw.endsWith("}")) {
            raw = raw.substring(2, raw.length() - 1);
        }
        return YamlConfigUtils.resolveProperty("${" + raw + "}", yamlFlat);
    }

    private static String extractConditionalProperty(String content) {
        Matcher m = CONDITIONAL_PROPERTY.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static String enclosingMethod(String content, int annoIndex) {
        Matcher m = METHOD.matcher(content);
        while (m.find()) {
            if ("class".equals(m.group(2)) || "interface".equals(m.group(2))) continue;
            if (m.start() > annoIndex) {
                return m.group(2);
            }
        }
        m.reset();
        String last = "onMessage";
        while (m.find()) {
            if ("class".equals(m.group(2)) || "interface".equals(m.group(2))) continue;
            if (m.start() < annoIndex) last = m.group(2);
        }
        return last;
    }

    private String attributes(String topic, String method, String groupId, String enabledProperty) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("topicShortId", topic);
            attrs.put("method", method);
            attrs.put("transport", "KAFKA");
            if (groupId != null) attrs.put("consumerGroup", groupId);
            if (enabledProperty != null) attrs.put("enabledProperty", enabledProperty);
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-").toLowerCase(Locale.ROOT);
    }
}

package io.testseer.backend.ingestion.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class YamlKafkaTopicExtractor {

    private static final Pattern TOPIC_NAME_KEY =
            Pattern.compile("(?:^|\\.)kafka\\.topics\\..+\\.topic-name$");
    private static final Pattern TOPIC_NAME_CAMEL_KEY =
            Pattern.compile("(?:^|\\.)kafka\\.topics\\..+\\.topicName$");

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.PubSubResourceFact> extract(List<YamlPubSubExtractor.ConfigFile> configFiles) {
        List<FactBatch.PubSubResourceFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (YamlPubSubExtractor.ConfigFile file : configFiles) {
            if (!isYaml(file.path())) continue;
            EnvLaneResolver.EnvProfile env = EnvLaneResolver.resolve(file.path());
            String module = EnvLaneResolver.resolveModuleName(file.path());

            for (YamlConfigUtils.FlatMapSource source : YamlConfigUtils.expandAndFlatten(file.path(), file.content())) {
                for (Map.Entry<String, String> entry : source.flat().entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (value == null || value.isBlank()) continue;
                    if (!isTopicNameKey(key)) continue;

                    String prefix = topicPrefix(key);
                    if (prefix == null) continue;

                    String resolvedTopic = YamlConfigUtils.resolveProperty(value, source.flat());
                    double confidence = resolvedTopic.equals(value) ? 0.95 : 0.85;

                    for (String role : rolesForPrefix(prefix, source.flat())) {
                        String dedupe = role + "|" + resolvedTopic + "|" + env.envLane();
                        if (!seen.add(dedupe)) continue;

                        results.add(buildTopicFact(
                                source.sourcePath(), env, module, key, resolvedTopic, role,
                                prefix, source.flat(), confidence));
                    }
                }
            }
        }
        return results;
    }

    private List<String> rolesForPrefix(String prefix, Map<String, String> flat) {
        List<String> roles = new ArrayList<>();
        if (YamlConfigUtils.isTruthy(flat.get(prefix + ".consumer.enabled"))
                || flat.containsKey(prefix + ".consumer.group-id")
                || flat.containsKey(prefix + ".consumer.groupId")) {
            roles.add("SUBSCRIBE");
        }
        if (YamlConfigUtils.isTruthy(flat.get(prefix + ".producer.enabled"))
                || hasProducerConfig(prefix, flat)) {
            roles.add("PUBLISH");
        }
        if (roles.isEmpty() && prefix.endsWith(".pipeline")) {
            roles.add("SUBSCRIBE");
        }
        if (roles.isEmpty()) {
            roles.add("PUBLISH");
        }
        return roles;
    }

    private static boolean hasProducerConfig(String prefix, Map<String, String> flat) {
        String producerPrefix = prefix + ".producer.";
        for (String key : flat.keySet()) {
            if (key.startsWith(producerPrefix)) {
                return true;
            }
        }
        return false;
    }

    private FactBatch.PubSubResourceFact buildTopicFact(
            String yamlPath,
            EnvLaneResolver.EnvProfile env,
            String module,
            String springKey,
            String topicName,
            String role,
            String prefix,
            Map<String, String> flat,
            double confidence) {

        String groupId = flat.get(prefix + ".consumer.group-id");
        if (groupId == null) {
            groupId = flat.get(prefix + ".consumer.groupId");
        }
        String enabledProperty = prefix + ".enabled";

        return new FactBatch.PubSubResourceFact(
                "TOPIC",
                topicName,
                env.envLane(),
                env.envProfile(),
                null,
                null,
                role,
                springKey,
                yamlPath,
                module,
                null,
                null,
                EnvLaneResolver.resolveWorkloadName(module),
                "YAML",
                confidence,
                kafkaAttributes(springKey, topicName, groupId, enabledProperty)
        );
    }

    private String kafkaAttributes(String springKey, String topicName, String groupId, String enabledProperty) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("transport", "KAFKA");
            attrs.put("springKey", springKey);
            attrs.put("topicName", topicName);
            if (groupId != null) {
                attrs.put("consumerGroup", groupId);
            }
            if (enabledProperty != null) {
                attrs.put("enabledProperty", enabledProperty);
            }
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return "{\"transport\":\"KAFKA\"}";
        }
    }

    private static boolean isTopicNameKey(String key) {
        return TOPIC_NAME_KEY.matcher(key).find() || TOPIC_NAME_CAMEL_KEY.matcher(key).find();
    }

    private static String topicPrefix(String key) {
        if (key.endsWith(".topic-name")) {
            return key.substring(0, key.length() - ".topic-name".length());
        }
        if (key.endsWith(".topicName")) {
            return key.substring(0, key.length() - ".topicName".length());
        }
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot) : null;
    }

    private boolean isYaml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    public static boolean isKafkaFact(FactBatch.PubSubResourceFact fact) {
        if (fact == null) return false;
        if (fact.springKey() != null && fact.springKey().contains("kafka.topics")) {
            return true;
        }
        String attrs = fact.attributes();
        return attrs != null && attrs.contains("\"transport\":\"KAFKA\"");
    }
}

package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical Kafka topic short ids and env-specific aliases for cross-repo joins (BL-050 P0). */
public final class KafkaTopicAliasIndex {

    private final Map<String, String> aliasToCanonical;

    private KafkaTopicAliasIndex(Map<String, String> aliasToCanonical) {
        this.aliasToCanonical = aliasToCanonical;
    }

    public static KafkaTopicAliasIndex empty() {
        return new KafkaTopicAliasIndex(Map.of());
    }

    public static KafkaTopicAliasIndex from(MessagingRulePack rulePack) {
        if (rulePack == null || rulePack.kafkaTopicAliases() == null || rulePack.kafkaTopicAliases().isEmpty()) {
            return empty();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (MessagingRulePack.KafkaTopicAliasRule rule : rulePack.kafkaTopicAliases()) {
            if (rule.logical() == null || rule.logical().isBlank()) {
                continue;
            }
            String logical = rule.logical().trim();
            map.putIfAbsent(normalize(logical), logical);
            if (rule.aliases() != null) {
                for (String alias : rule.aliases()) {
                    if (alias != null && !alias.isBlank()) {
                        map.put(normalize(alias), logical);
                    }
                }
            }
        }
        return new KafkaTopicAliasIndex(Map.copyOf(map));
    }

    public String canonical(String topic) {
        if (topic == null || topic.isBlank()) {
            return topic;
        }
        return aliasToCanonical.getOrDefault(normalize(topic), topic);
    }

    public boolean equivalent(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return canonical(left).equalsIgnoreCase(canonical(right));
    }

    private static String normalize(String topic) {
        return topic.trim().toUpperCase(Locale.ROOT);
    }
}

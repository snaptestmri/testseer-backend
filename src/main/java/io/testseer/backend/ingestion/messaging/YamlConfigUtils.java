package io.testseer.backend.ingestion.messaging;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YamlConfigUtils {

    private static final Pattern PROPERTY_REF =
            Pattern.compile("\\$\\{([^}:]+)(?::[^}]*)?}");

    private YamlConfigUtils() {}

    public record FlatMapSource(String sourcePath, Map<String, String> flat) {}

    public static List<FlatMapSource> expandAndFlatten(String yamlPath, String content) {
        List<FlatMapSource> sources = new ArrayList<>();
        Map<String, Object> root = parseYaml(content);
        if (root == null) {
            return sources;
        }
        if (isConfigMap(root)) {
            Object data = root.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                    if (!(entry.getValue() instanceof String embedded)) continue;
                    if (!looksLikeYaml(embedded)) continue;
                    Map<String, Object> inner = parseYaml(embedded);
                    if (inner != null) {
                        sources.add(new FlatMapSource(
                                yamlPath + "#" + entry.getKey(),
                                flatten(inner, "")));
                    }
                }
            }
        }
        sources.add(new FlatMapSource(yamlPath, flatten(root, "")));
        return sources;
    }

    public static Map<String, String> mergeFlatMaps(List<FlatMapSource> sources) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (FlatMapSource source : sources) {
            merged.putAll(source.flat());
        }
        return merged;
    }

    public static String resolveProperty(String value, Map<String, String> flat) {
        if (value == null || flat == null) return value;
        Matcher matcher = PROPERTY_REF.matcher(value);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String key = matcher.group(1);
            String resolved = flat.get(key);
            if (resolved != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
                changed = true;
            }
        }
        matcher.appendTail(sb);
        return changed ? sb.toString() : value;
    }

    public static boolean isTruthy(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseYaml(String content) {
        try {
            Object parsed = new Yaml().load(content);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
            // skip malformed yaml
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> flatten(Map<String, Object> map, String prefix) {
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

    private static boolean isConfigMap(Map<String, Object> root) {
        Object kind = root.get("kind");
        return kind != null && "ConfigMap".equalsIgnoreCase(String.valueOf(kind));
    }

    private static boolean looksLikeYaml(String content) {
        String trimmed = content.stripLeading();
        return trimmed.startsWith("#")
                || trimmed.startsWith("spring:")
                || trimmed.startsWith("kafka:")
                || trimmed.contains("\nkafka:")
                || trimmed.contains("\nspring:");
    }
}

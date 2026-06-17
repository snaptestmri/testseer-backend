package io.testseer.backend.ingestion.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.EnvLaneResolver;
import io.testseer.backend.ingestion.messaging.YamlConfigUtils;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class YamlExternalEndpointExtractor {

    private static final Pattern URL_VALUE = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_VALUE = Pattern.compile("^/\\S+$");
    private static final Pattern REST_URI_KEY = Pattern.compile(
            "^(rest\\.apis\\.|rest-clients\\.).+\\.uri$");
    private static final Pattern URL_KEY = Pattern.compile(
            "(?i)(^|\\.)((offer-)?endpoint|partner-publish-details-endpoint|login-base-url|"
                    + "promo-base-url|base-url|webhook-url|[-_]url|uri)$");

    private final ObjectMapper mapper = new ObjectMapper();

    public record YamlEndpointCandidate(
            String configKey,
            String urlResolved,
            String envLane,
            String envProfile,
            String yamlPath,
            String boundary,
            String httpMethod,
            String topicName
    ) {
        public YamlEndpointCandidate(
                String configKey,
                String urlResolved,
                String envLane,
                String envProfile,
                String yamlPath,
                String boundary) {
            this(configKey, urlResolved, envLane, envProfile, yamlPath, boundary, null, null);
        }
    }

    public List<YamlEndpointCandidate> extract(List<YamlPubSubExtractor.ConfigFile> configFiles) {
        List<YamlEndpointCandidate> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (YamlPubSubExtractor.ConfigFile file : configFiles) {
            if (!isYaml(file.path())) continue;

            for (YamlConfigUtils.FlatMapSource source : YamlConfigUtils.expandAndFlatten(
                    file.path(), file.content())) {
                EnvLaneResolver.EnvProfile env = EnvLaneResolver.resolve(source.sourcePath(), source.flat());

                for (Map.Entry<String, String> e : source.flat().entrySet()) {
                    String key = e.getKey();
                    String rawValue = e.getValue();
                    if (rawValue == null || rawValue.isBlank()) continue;
                    String value = YamlConfigUtils.resolveProperty(rawValue.trim(), source.flat());
                    if (value == null || value.isBlank()) continue;
                    if (!isEndpointUriKey(key, value)) continue;
                    if (value.contains("<ENVIRONMENT_SPECIFIC>")) continue;

                    String dedupe = key + "|" + env.envLane() + "|" + value;
                    if (!seen.add(dedupe)) continue;

                    String httpMethod = siblingValue(source.flat(), key, "method");
                    String topicName = siblingValue(source.flat(), key, "topic-name");

                    results.add(new YamlEndpointCandidate(
                            key,
                            value,
                            env.envLane(),
                            env.envProfile(),
                            source.sourcePath(),
                            classifyBoundary(value),
                            httpMethod,
                            topicName
                    ));
                }
            }
        }
        return results;
    }

    static boolean isEndpointUriKey(String key, String value) {
        if (REST_URI_KEY.matcher(key).matches()) {
            return URL_VALUE.matcher(value).matches() || PATH_VALUE.matcher(value).matches();
        }
        return URL_KEY.matcher(key).find() && URL_VALUE.matcher(value).matches();
    }

    public FactBatch.ExternalEndpointFact toFact(
            YamlEndpointCandidate candidate,
            String endpointId,
            String partnerSlug,
            String operation,
            String httpMethod,
            String callerClassFqn,
            String clientClassFqn,
            String flowStep,
            String authScheme,
            double confidence) {
        return new FactBatch.ExternalEndpointFact(
                endpointId,
                partnerSlug,
                operation,
                httpMethod,
                templateFromUrl(candidate.urlResolved()),
                candidate.urlResolved(),
                candidate.envLane(),
                candidate.boundary(),
                candidate.configKey(),
                candidate.yamlPath(),
                callerClassFqn,
                clientClassFqn,
                flowStep,
                authScheme,
                "YAML",
                confidence,
                attributes(candidate)
        );
    }

    static String classifyBoundary(String url) {
        if (url != null && url.startsWith("/")) {
            return "INTERNAL";
        }
        String host = extractHost(url);
        if (host == null) return "EXTERNAL";
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.contains("hy-vee.com")
                || lower.contains("mockapi")
                || lower.endsWith(".external")) {
            return "EXTERNAL";
        }
        if (lower.contains("quotient.com")
                || lower.startsWith("riq-")
                || lower.contains("localhost")
                || lower.matches("^10\\.\\d+\\.\\d+\\.\\d+$")) {
            return "INTERNAL";
        }
        return "EXTERNAL";
    }

    private static String extractHost(String url) {
        if (url == null) return null;
        try {
            return URI.create(url.replace("%s", "placeholder")).getHost();
        } catch (Exception ex) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^https?://([^/]+)")
                    .matcher(url);
            return m.find() ? m.group(1) : null;
        }
    }

    static String templateFromUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/{id}")
                .replaceAll("%s", "{id}");
    }

    static String kebabToCamel(String kebab) {
        if (kebab == null || !kebab.contains("-")) return kebab;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String attributes(YamlEndpointCandidate candidate) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("configKey", candidate.configKey());
            attrs.put("envProfile", candidate.envProfile() != null ? candidate.envProfile() : "unknown");
            if (candidate.httpMethod() != null && !candidate.httpMethod().isBlank()) {
                attrs.put("httpMethod", candidate.httpMethod());
            }
            if (candidate.topicName() != null && !candidate.topicName().isBlank()) {
                attrs.put("topicName", candidate.topicName());
            }
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String siblingValue(Map<String, String> flat, String uriKey, String leaf) {
        int lastDot = uriKey.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String parent = uriKey.substring(0, lastDot);
        return flat.get(parent + "." + leaf);
    }

    private boolean isYaml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }
}

package io.testseer.backend.ingestion.messaging;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnvLaneResolver {

    private static final Pattern APPLICATION_PROFILE =
            Pattern.compile("application(?:-([a-z0-9-]+))?\\.(ya?ml)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern K8S_MANIFEST_LANE =
            Pattern.compile("/kubernetes-manifests/([^/]+)/", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIG_MAP_LANE =
            Pattern.compile("\\.([a-z0-9]+)\\.config-map\\.(ya?ml)(?:#|$)", Pattern.CASE_INSENSITIVE);

    private EnvLaneResolver() {}

    public record EnvProfile(String envLane, String envProfile) {}

    public static EnvProfile resolve(String yamlPath) {
        return resolve(yamlPath, null);
    }

    public static EnvProfile resolve(String yamlPath, Map<String, String> flat) {
        String normalized = yamlPath.replace('\\', '/');
        String fileName = normalized;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }

        Matcher app = APPLICATION_PROFILE.matcher(fileName);
        if (app.find()) {
            String suffix = app.group(1);
            if (suffix != null && !suffix.isBlank()) {
                return laneFromSuffix(suffix);
            }
        }

        Matcher manifest = K8S_MANIFEST_LANE.matcher(normalized);
        if (manifest.find()) {
            return laneFromSuffix(manifest.group(1));
        }

        Matcher configMap = CONFIG_MAP_LANE.matcher(normalized);
        if (configMap.find()) {
            return laneFromSuffix(configMap.group(1));
        }

        if (flat != null) {
            String active = firstNonBlank(
                    flat.get("spring.profiles.active"),
                    flat.get("spring.cloud.config.profile"));
            if (active != null) {
                return laneFromSuffix(active);
            }
        }

        return new EnvProfile("unknown", "base");
    }

    private static EnvProfile laneFromSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return new EnvProfile("unknown", "base");
        }
        String lane = suffix.toLowerCase(Locale.ROOT);
        if (lane.startsWith("pdn")) return new EnvProfile("pdn", suffix);
        if (lane.startsWith("qa")) return new EnvProfile("qa", suffix);
        if (lane.startsWith("prod")) return new EnvProfile("prod", suffix);
        if (lane.startsWith("dev")) return new EnvProfile("dev", suffix);
        if (lane.startsWith("pn")) return new EnvProfile(lane, suffix);
        return new EnvProfile("unknown", suffix);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    public static String resolveModuleName(String filePath) {
        String normalized = filePath.replace('\\', '/');
        String fromResources = moduleBeforeSrcMarker(normalized, "/src/main/resources/");
        if (fromResources != null) {
            return fromResources;
        }
        String fromJava = moduleBeforeSrcMarker(normalized, "/src/main/java/");
        if (fromJava != null) {
            return fromJava;
        }
        String fromKotlin = moduleBeforeSrcMarker(normalized, "/src/main/kotlin/");
        if (fromKotlin != null) {
            return fromKotlin;
        }
        int firstSlash = normalized.indexOf('/');
        return firstSlash > 0 ? normalized.substring(0, firstSlash) : null;
    }

    /** Returns the Maven/Gradle module directory immediately before a {@code /src/main/...} marker. */
    private static String moduleBeforeSrcMarker(String normalized, String marker) {
        int srcIdx = normalized.indexOf(marker);
        if (srcIdx <= 0) {
            return null;
        }
        String prefix = normalized.substring(0, srcIdx);
        int modSlash = prefix.lastIndexOf('/');
        return modSlash >= 0 ? prefix.substring(modSlash + 1) : prefix;
    }

    public static String resolveWorkloadName(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) return null;
        if (moduleName.endsWith("-ns")) return moduleName;
        return moduleName + "-ns";
    }
}

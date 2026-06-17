package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.ingestion.ParsedModel;

import java.util.List;
import java.util.Locale;

/** Shared heuristics for Spring Boot JVM launcher classes (BL-054). */
public final class SpringBootLauncherDetector {

    private SpringBootLauncherDetector() {}

    public static boolean isLongRunningSpringBootMain(ParsedModel model, String content) {
        if (model == null || model.classFqn() == null || content == null) {
            return false;
        }
        if (isExcludedPath(model.filePath())) {
            return false;
        }
        if (!hasSpringBootApplication(model, content)) {
            return false;
        }
        if (!content.contains("SpringApplication.run")) {
            return false;
        }
        if (!content.contains("public static void main(")) {
            return false;
        }
        if (content.contains("CommandLineRunner")) {
            return false;
        }
        return true;
    }

    static boolean isExcludedPath(String filePath) {
        if (filePath == null) {
            return true;
        }
        String normalized = filePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("evaluation-jobs/");
    }

    private static boolean hasSpringBootApplication(ParsedModel model, String content) {
        List<String> annotations = model.annotations();
        if (annotations != null) {
            for (String annotation : annotations) {
                if (annotation != null && annotation.contains("SpringBootApplication")) {
                    return true;
                }
            }
        }
        return content.contains("@SpringBootApplication");
    }

    static String moduleDirFromJavaPath(String filePath) {
        if (filePath == null) {
            return null;
        }
        String normalized = filePath.replace('\\', '/');
        int srcIdx = normalized.indexOf("/src/main/java/");
        if (srcIdx < 0) {
            return null;
        }
        String prefix = normalized.substring(0, srcIdx);
        int slash = prefix.lastIndexOf('/');
        return slash >= 0 ? prefix.substring(slash + 1) : prefix;
    }
}

package io.testseer.backend.ingestion.triggers;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves {@code spring-boot-maven-plugin} / {@code start-class} mainClass from pom.xml content. */
public final class MavenMainClassResolver {

    private static final Pattern MAIN_CLASS_TAG =
            Pattern.compile("<mainClass>\\s*([\\w.]+)\\s*</mainClass>");
    private static final Pattern START_CLASS_TAG =
            Pattern.compile("<start-class>\\s*([\\w.]+)\\s*</start-class>");

    private MavenMainClassResolver() {}

    public static Map<String, String> resolveFromContentByPath(Map<String, String> contentByPath) {
        Map<String, String> byModuleDir = new LinkedHashMap<>();
        if (contentByPath == null) {
            return byModuleDir;
        }
        for (Map.Entry<String, String> entry : contentByPath.entrySet()) {
            String path = entry.getKey();
            if (path == null || !path.toLowerCase(Locale.ROOT).endsWith("pom.xml")) {
                continue;
            }
            String mainClass = extractMainClass(entry.getValue());
            if (mainClass == null) {
                continue;
            }
            String moduleDir = moduleDirFromPomPath(path);
            byModuleDir.put(mainClass, moduleDir);
            if (moduleDir != null) {
                byModuleDir.put(moduleDir, mainClass);
            }
        }
        return byModuleDir;
    }

    static String extractMainClass(String pomContent) {
        if (pomContent == null || pomContent.isBlank()) {
            return null;
        }
        Matcher main = MAIN_CLASS_TAG.matcher(pomContent);
        if (main.find()) {
            return main.group(1).trim();
        }
        Matcher start = START_CLASS_TAG.matcher(pomContent);
        if (start.find()) {
            return start.group(1).trim();
        }
        return null;
    }

    static String moduleDirFromPomPath(String pomPath) {
        String normalized = pomPath.replace('\\', '/');
        if (!normalized.endsWith("/pom.xml")) {
            return null;
        }
        String parent = normalized.substring(0, normalized.length() - "/pom.xml".length());
        int slash = parent.lastIndexOf('/');
        return slash >= 0 ? parent.substring(slash + 1) : parent;
    }
}

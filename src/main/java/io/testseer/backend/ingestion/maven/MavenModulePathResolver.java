package io.testseer.backend.ingestion.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Derives Maven module paths from workspace source/config roots for scoped POM fetch and fact slicing. */
public final class MavenModulePathResolver {

    private MavenModulePathResolver() {}

    public static List<String> pomRootsFromProfile(List<String> sourceRoots, List<String> configRoots) {
        Set<String> roots = new LinkedHashSet<>();
        collectRoots(sourceRoots, roots);
        collectRoots(configRoots, roots);
        return List.copyOf(roots);
    }

    private static void collectRoots(List<String> paths, Set<String> roots) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            String module = modulePathFromSourceRoot(path);
            if (module != null) {
                roots.add(module);
            }
        }
    }

    /**
     * Maps a workspace root like {@code platform-data/src/main/java} to Maven module path
     * {@code platform-data}; {@code src/main/java} maps to repo root ({@code ""}).
     */
    public static String modulePathFromSourceRoot(String sourceRoot) {
        if (sourceRoot == null || sourceRoot.isBlank()) {
            return "";
        }
        String normalized = sourceRoot.replace('\\', '/').replaceAll("/+$", "");
        int srcIdx = normalized.indexOf("/src/");
        if (srcIdx >= 0) {
            return normalized.substring(0, srcIdx);
        }
        if (normalized.equals("src/main/java") || normalized.startsWith("src/")) {
            return "";
        }
        return normalized;
    }

    /** Empty {@code pomRoots} means whole-repo scope. */
    public static boolean moduleInScope(String modulePath, List<String> pomRoots) {
        if (pomRoots == null || pomRoots.isEmpty()) {
            return true;
        }
        String mp = modulePath != null ? modulePath : "";
        if (mp.isEmpty()) {
            return true;
        }
        for (String root : pomRoots) {
            String r = root != null ? root : "";
            if (mp.equals(r) || mp.startsWith(r + "/") || (!r.isEmpty() && r.startsWith(mp + "/"))) {
                return true;
            }
        }
        return false;
    }

    public static List<String> normalizePomRoots(List<String> pomRoots) {
        if (pomRoots == null || pomRoots.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String root : pomRoots) {
            if (root == null) {
                continue;
            }
            String r = root.replace('\\', '/').replaceAll("/+$", "");
            normalized.add(r);
        }
        return List.copyOf(normalized);
    }
}

package io.testseer.backend.query;

import java.util.ArrayList;
import java.util.List;

/**
 * KFK-08: filters entry triggers to a monorepo module via {@code workspace.yml} source roots
 * or an explicit {@code sourceRootPrefix}.
 */
public final class EntryTriggerScopeFilter {

    private EntryTriggerScopeFilter() {}

    public static List<String> normalizeRoots(List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String path = root.replace('\\', '/').replaceAll("/+$", "");
            normalized.add(path.endsWith("/") ? path : path + "/");
        }
        return normalized;
    }

    public static boolean matches(
            EntryFlowService.EntryTriggerView trigger, List<String> sourceRootPrefixes) {
        if (sourceRootPrefixes == null || sourceRootPrefixes.isEmpty()) {
            return true;
        }
        String sourceRef = trigger.sourceRef();
        if (sourceRef == null || sourceRef.isBlank()) {
            return false;
        }
        String normalized = sourceRef.replace('\\', '/');
        for (String root : sourceRootPrefixes) {
            String prefix = root.endsWith("/") ? root : root + "/";
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static List<EntryFlowService.EntryTriggerView> filter(
            List<EntryFlowService.EntryTriggerView> triggers, List<String> sourceRootPrefixes) {
        if (sourceRootPrefixes == null || sourceRootPrefixes.isEmpty()) {
            return triggers;
        }
        return triggers.stream()
                .filter(t -> matches(t, sourceRootPrefixes))
                .toList();
    }
}

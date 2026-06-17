package io.testseer.backend.query;

/** Filters production vs test handler symbols and source paths (UP-GAP-06). */
public final class HandlerScopeFilter {

    private HandlerScopeFilter() {}

    public static boolean isProductionHandler(String handlerClassFqn) {
        if (handlerClassFqn == null || handlerClassFqn.isBlank()) {
            return false;
        }
        String simple = simpleName(handlerClassFqn);
        if (simple.endsWith("Test") || simple.endsWith("IT") || simple.endsWith("IntTest")) {
            return false;
        }
        String lower = handlerClassFqn.toLowerCase();
        return !lower.contains(".inttest.") && !lower.contains(".test.");
    }

    public static boolean isTestSourcePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        return filePath.replace('\\', '/').contains("src/test/java");
    }

    private static String simpleName(String fqn) {
        int hash = fqn.indexOf('#');
        String classPart = hash > 0 ? fqn.substring(0, hash) : fqn;
        int dot = classPart.lastIndexOf('.');
        return dot >= 0 ? classPart.substring(dot + 1) : classPart;
    }
}

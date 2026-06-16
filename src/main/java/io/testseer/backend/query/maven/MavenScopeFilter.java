package io.testseer.backend.query.maven;

import java.util.List;
import java.util.Locale;

/**
 * Maps API scope query params to Maven dependency scopes stored at index time.
 * {@code runtime} matches {@code mvn dependency:tree -Dscope=runtime} (compile + runtime on classpath).
 */
final class MavenScopeFilter {

    private MavenScopeFilter() {}

    static boolean matches(String storedScope, String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank() || "all".equalsIgnoreCase(requestedScope)) {
            return true;
        }
        String stored = normalize(storedScope);
        String requested = normalize(requestedScope);
        return switch (requested) {
            case "runtime" -> "compile".equals(stored) || "runtime".equals(stored);
            case "compile" -> "compile".equals(stored);
            case "test" -> "test".equals(stored);
            case "provided" -> "provided".equals(stored);
            case "system" -> "system".equals(stored);
            default -> requested.equals(stored);
        };
    }

    static List<String> sqlScopes(String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank() || "all".equalsIgnoreCase(requestedScope)) {
            return List.of();
        }
        return switch (normalize(requestedScope)) {
            case "runtime" -> List.of("compile", "runtime");
            case "compile", "test", "provided", "system" -> List.of(normalize(requestedScope));
            default -> List.of(normalize(requestedScope));
        };
    }

    private static String normalize(String scope) {
        return scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
    }
}

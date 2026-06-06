package io.testseer.backend.analysis;

public final class TestClassMatcher {

    private TestClassMatcher() {}

    public static boolean matches(String productionSimpleName, String testFqn) {
        String testSimple = simpleName(testFqn);
        return testSimple.equals(productionSimpleName + "Test")
                || testSimple.equals(productionSimpleName + "Tests")
                || testSimple.equals(productionSimpleName + "IT")
                || testSimple.startsWith("Test" + productionSimpleName);
    }

    public static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}

package io.testseer.backend.ingestion.consistency;

/** Stable, DB-bounded scenario identifiers for consistency_scenario_facts.scenario_id. */
final class ConsistencyScenarioIds {

    static final int MAX_LENGTH = 255;

    private ConsistencyScenarioIds() {}

    static String fit(String candidate) {
        if (candidate == null || candidate.length() <= MAX_LENGTH) {
            return candidate;
        }
        String suffix = "-" + Integer.toHexString(candidate.hashCode());
        int prefixLen = MAX_LENGTH - suffix.length();
        return candidate.substring(0, Math.max(0, prefixLen)) + suffix;
    }
}

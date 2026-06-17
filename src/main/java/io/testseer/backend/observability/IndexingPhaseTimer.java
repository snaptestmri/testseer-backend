package io.testseer.backend.observability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lap timer for indexing pipeline phases. Each {@link #lap} records elapsed ms since the previous lap.
 */
public final class IndexingPhaseTimer {

    private final long startNanos = System.nanoTime();
    private long lapStartNanos = startNanos;
    private final LinkedHashMap<String, Long> phaseMs = new LinkedHashMap<>();

    public void lap(String phase) {
        long now = System.nanoTime();
        phaseMs.put(phase, (now - lapStartNanos) / 1_000_000L);
        lapStartNanos = now;
    }

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public Map<String, Long> phases() {
        return Map.copyOf(phaseMs);
    }

    public String formatPhases() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : phaseMs.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append("ms");
        }
        return sb.toString();
    }
}

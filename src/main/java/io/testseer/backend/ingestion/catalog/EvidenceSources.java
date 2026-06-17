package io.testseer.backend.ingestion.catalog;

import java.util.Arrays;
import java.util.LinkedHashSet;

/** Builds deduplicated, length-bounded evidence_source tag chains for catalog facts. */
public final class EvidenceSources {

    public static final int MAX_LENGTH = 255;

    private EvidenceSources() {}

    public static String append(String existing, String tag) {
        if (tag == null || tag.isBlank()) {
            return fit(existing);
        }
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split("\\+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(parts::add);
        }
        parts.add(tag.trim());
        return fit(String.join("+", parts));
    }

    private static String fit(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= MAX_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LENGTH);
    }
}

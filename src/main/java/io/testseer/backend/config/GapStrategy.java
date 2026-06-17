package io.testseer.backend.config;

import java.util.Locale;

/** How consistency gap checks apply to a rule or inferred scenario (CON §11.2). */
public enum GapStrategy {
    INDEXED,
    REACHABLE,
    NONE;

    public static GapStrategy of(MessagingRulePack.ConsistencyRule rule) {
        if (rule == null || rule.pattern() == null) {
            return NONE;
        }
        if (rule.gapStrategy() != null && !rule.gapStrategy().isBlank()) {
            return parse(rule.gapStrategy());
        }
        return defaultForPattern(rule.pattern());
    }

    public static GapStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return GapStrategy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    public static GapStrategy defaultForPattern(String pattern) {
        if (pattern == null) {
            return NONE;
        }
        return switch (pattern.toUpperCase(Locale.ROOT)) {
            case "DUAL_WRITE", "DUAL_WRITE_SAME_HANDLER", "ASYNC_MIRROR", "MIRROR",
                    "DUAL_READ_FALLBACK" -> INDEXED;
            case "CO_TABLE_INVARIANT", "MULTI_TABLE_DOMAIN", "CROSS_STORE_WRITE",
                    "BUSINESS_FLAG", "ASYNC_BATCH_INGEST" -> REACHABLE;
            default -> NONE;
        };
    }
}

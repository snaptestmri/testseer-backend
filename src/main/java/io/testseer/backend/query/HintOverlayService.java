package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dedupes inferred vs curated consistency hints; prefers stable rule-pack scenario ids. */
final class HintOverlayService {

    private HintOverlayService() {}

    static List<ConsistencyHintView> overlay(
            List<ConsistencyHintView> matched,
            Map<String, MessagingRulePack.ConsistencyRule> rules,
            ConsistencyScenarioMatcher.TouchpointContext ctx) {
        if (matched.isEmpty() || rules == null || rules.isEmpty()) {
            return matched;
        }

        Map<String, ConsistencyHintView> byId = new LinkedHashMap<>();
        for (ConsistencyHintView hint : matched) {
            byId.put(hint.scenarioId(), hint);
        }

        Set<String> written = writtenTables(ctx);
        for (Map.Entry<String, MessagingRulePack.ConsistencyRule> entry : rules.entrySet()) {
            MessagingRulePack.ConsistencyRule rule = entry.getValue();
            if (rule == null || rule.pattern() == null) continue;

            ConsistencyQueryService.ConsistencyScenarioView synthetic =
                    syntheticScenario(entry.getKey(), rule);
            if (!ConsistencyScenarioMatcher.allRequiredParticipantsWritten(synthetic, ctx)) {
                continue;
            }

            String curatedId = entry.getKey();
            ConsistencyHintView existingCurated = byId.get(curatedId);
            if (existingCurated != null) {
                continue;
            }

            ConsistencyHintView inferred = findOverlappingInferred(byId.values(), rule, written);
            if (inferred != null) {
                byId.remove(inferred.scenarioId());
                byId.put(curatedId, mergeCurated(inferred, curatedId, rule));
            }
        }

        return pruneSubsets(List.copyOf(byId.values()));
    }

    private static ConsistencyHintView findOverlappingInferred(
            Iterable<ConsistencyHintView> hints,
            MessagingRulePack.ConsistencyRule rule,
            Set<String> written) {
        String family = patternFamily(rule.pattern());
        for (ConsistencyHintView hint : hints) {
            if ("RULE_PACK".equalsIgnoreCase(hint.evidenceSource())) continue;
            if (!patternFamily(hint.pattern()).equals(family)) continue;
            if (participantsOverlap(hint, rule, written)) {
                return hint;
            }
        }
        return null;
    }

    private static ConsistencyHintView mergeCurated(
            ConsistencyHintView inferred, String curatedId, MessagingRulePack.ConsistencyRule rule) {
        return new ConsistencyHintView(
                curatedId,
                rule.pattern() != null ? rule.pattern() : inferred.pattern(),
                rule.primaryStore() != null ? rule.primaryStore() : inferred.primaryStore(),
                rule.primaryPhysical() != null ? rule.primaryPhysical() : inferred.primaryPhysical(),
                rule.correlationKeys() != null && !rule.correlationKeys().isEmpty()
                        ? rule.correlationKeys() : inferred.correlationKeys(),
                inferred.participants(),
                inferred.pollStrategy(),
                inferred.invariants(),
                "RULE_PACK+INFERRED",
                Math.max(inferred.confidence(), 0.95),
                inferred.downstreamGates()
        );
    }

    private static boolean participantsOverlap(
            ConsistencyHintView hint,
            MessagingRulePack.ConsistencyRule rule,
            Set<String> written) {
        if (rule.participants() == null || rule.participants().isEmpty()) {
            return false;
        }
        for (MessagingRulePack.ConsistencyParticipantRule p : rule.participants()) {
            String role = p.role() != null ? p.role().toUpperCase(Locale.ROOT) : "";
            if (!"PRIMARY".equals(role) && !role.startsWith("REQUIRED") && !"SECONDARY".equals(role)) {
                continue;
            }
            if (p.physicalName() != null && written.contains(normalize(p.physicalName()))) {
                return true;
            }
        }
        return hint.participants() != null && !hint.participants().isEmpty();
    }

    private static List<ConsistencyHintView> pruneSubsets(List<ConsistencyHintView> hints) {
        if (hints.size() <= 1) return hints;
        List<ConsistencyHintView> pruned = new ArrayList<>();
        for (ConsistencyHintView candidate : hints) {
            Set<String> sig = participantSignature(candidate);
            boolean subset = false;
            for (ConsistencyHintView other : hints) {
                if (other == candidate) continue;
                Set<String> otherSig = participantSignature(other);
                if (!otherSig.isEmpty() && sig.size() < otherSig.size() && otherSig.containsAll(sig)) {
                    subset = true;
                    break;
                }
            }
            if (!subset) {
                pruned.add(candidate);
            }
        }
        return pruned;
    }

    private static Set<String> participantSignature(ConsistencyHintView hint) {
        Set<String> sig = new LinkedHashSet<>();
        if (hint.primaryPhysical() != null) sig.add(normalize(hint.primaryPhysical()));
        if (hint.participants() != null) {
            for (ConsistencyParticipantHintView p : hint.participants()) {
                if (p.physicalName() != null) sig.add(normalize(p.physicalName()));
            }
        }
        return sig;
    }

    private static Set<String> writtenTables(ConsistencyScenarioMatcher.TouchpointContext ctx) {
        Set<String> written = new LinkedHashSet<>();
        for (MessagingFlowService.DataAccessView row : ctx.touchpoints()) {
            if (!"WRITE".equalsIgnoreCase(row.operation())) continue;
            if (row.physicalName() != null) written.add(normalize(row.physicalName()));
            if (row.tableOrEntity() != null) written.add(normalize(row.tableOrEntity()));
        }
        return written;
    }

    private static String patternFamily(String pattern) {
        if (pattern == null) return "";
        return switch (pattern.toUpperCase(Locale.ROOT)) {
            case "DUAL_WRITE", "DUAL_WRITE_SAME_HANDLER" -> "DUAL_WRITE";
            case "MULTI_TABLE_DOMAIN" -> "MULTI_TABLE";
            case "CROSS_STORE_WRITE" -> "CROSS_STORE";
            default -> pattern.toUpperCase(Locale.ROOT);
        };
    }

    private static ConsistencyQueryService.ConsistencyScenarioView syntheticScenario(
            String id, MessagingRulePack.ConsistencyRule rule) {
        List<ConsistencyParticipantHintView> participants = rule.participants() == null
                ? List.of()
                : rule.participants().stream()
                        .map(p -> new ConsistencyParticipantHintView(
                                p.storeType(), p.physicalName(), p.role(), p.via(), p.lagClass()))
                        .toList();
        return new ConsistencyQueryService.ConsistencyScenarioView(
                id, rule.pattern(), "RULE_PACK", id,
                rule.primaryStore(), rule.primaryPhysical(),
                rule.correlationKeys(), participants, null, List.of(),
                "RULE_PACK", 0.95, null);
    }

    private static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }
}

package io.testseer.backend.query;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Links downstream flow gates in later trace hops to writer-side consistency hints. */
@Component
public class CrossRepoGateLinker {

    private static final Set<String> DOWNSTREAM_PATTERNS = Set.of(
            "CO_TABLE_INVARIANT", "MULTI_TABLE_DOMAIN", "PROPAGATION_LAG", "CROSS_STORE_WRITE");

    public ConsistencyHintView attachDownstreamGates(
            ConsistencyHintView hint,
            int writerHopOrder,
            CrossRepoTraceContext ctx) {
        if (hint.pattern() == null
                || !DOWNSTREAM_PATTERNS.contains(hint.pattern().toUpperCase(Locale.ROOT))
                || ctx == null) {
            return hint;
        }
        Set<String> participants = normalizedParticipants(hint);
        if (participants.isEmpty()) {
            return hint;
        }

        List<DownstreamGateView> downstream = new ArrayList<>();
        for (MessagingFlowService.CrossRepoHop hop : ctx.hops()) {
            if (hop.order() <= writerHopOrder) {
                continue;
            }
            for (MessagingFlowService.PubSubOrgView sub : hop.subscribers()) {
                List<MessagingFlowService.FlowGateView> gates =
                        ctx.gatesByService().getOrDefault(sub.serviceId(), List.of());
                for (MessagingFlowService.FlowGateView gate : gates) {
                    if (!"BUSINESS_RULE".equalsIgnoreCase(gate.gateKind())) {
                        continue;
                    }
                    if (!gateMatchesParticipant(gate.gateKey(), participants)) {
                        continue;
                    }
                    downstream.add(new DownstreamGateView(
                            sub.serviceId(),
                            sub.repo(),
                            hop.order(),
                            gate.gateKey(),
                            gate.requiredValue(),
                            gate.effectWhenFail(),
                            gate.testPrecondition()));
                }
            }
        }
        if (downstream.isEmpty()) {
            return hint;
        }
        return hint.withDownstreamGates(downstream);
    }

    static Set<String> normalizedParticipants(ConsistencyHintView hint) {
        Set<String> names = new LinkedHashSet<>();
        if (hint.primaryPhysical() != null && !hint.primaryPhysical().isBlank()) {
            names.add(normalize(hint.primaryPhysical()));
        }
        if (hint.participants() != null) {
            for (ConsistencyParticipantHintView p : hint.participants()) {
                String role = p.role() != null ? p.role().toUpperCase(Locale.ROOT) : "";
                if ("PRIMARY".equals(role) || role.startsWith("REQUIRED") || "SECONDARY".equals(role)) {
                    if (p.physicalName() != null && !p.physicalName().isBlank()) {
                        names.add(normalize(p.physicalName()));
                    }
                }
            }
        }
        return names;
    }

    static boolean gateMatchesParticipant(String gateKey, Set<String> participants) {
        if (gateKey == null || gateKey.isBlank()) {
            return false;
        }
        String prefix = extractTablePrefix(gateKey);
        return participants.contains(normalize(prefix));
    }

    static String extractTablePrefix(String gateKey) {
        int dot = gateKey.indexOf('.');
        return dot > 0 ? gateKey.substring(0, dot) : gateKey;
    }

    static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }
}

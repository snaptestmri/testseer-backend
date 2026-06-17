package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Assembles invariants for matched consistency hints (YAML + derived co-write + gates). */
@Service
public class InvariantDeriver {

    public List<ConsistencyInvariantHintView> derive(
            String serviceId,
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            MessagingRulePack.ConsistencyRule rule,
            List<MessagingFlowService.DataAccessView> touchpoints,
            CrossRepoTraceContext traceContext) {
        Map<String, ConsistencyInvariantHintView> merged = new LinkedHashMap<>();
        if (rule != null && rule.invariants() != null) {
            for (MessagingRulePack.ConsistencyInvariantRule inv : rule.invariants()) {
                String key = invariantKey(inv.kind(), inv.description());
                merged.put(key, new ConsistencyInvariantHintView(
                        inv.kind(), inv.description(), inv.pollHint()));
            }
        }
        if (scenario.invariants() != null) {
            for (ConsistencyInvariantHintView inv : scenario.invariants()) {
                merged.putIfAbsent(invariantKey(inv.kind(), inv.description()), inv);
            }
        }
        for (ConsistencyInvariantHintView derived : deriveCoWrite(scenario, touchpoints)) {
            merged.putIfAbsent(invariantKey(derived.kind(), derived.description()), derived);
        }
        if (serviceId != null) {
            for (ConsistencyInvariantHintView gate : deriveGate(scenario, touchpoints, traceContext, serviceId)) {
                merged.putIfAbsent(invariantKey(gate.kind(), gate.description()), gate);
            }
        }
        return List.copyOf(merged.values());
    }

    private List<ConsistencyInvariantHintView> deriveCoWrite(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            List<MessagingFlowService.DataAccessView> touchpoints) {
        Set<String> written = new LinkedHashSet<>();
        for (MessagingFlowService.DataAccessView row : touchpoints) {
            if (!"WRITE".equalsIgnoreCase(row.operation())) continue;
            written.addAll(normalizedRowTables(row));
        }
        if (written.isEmpty()) {
            return List.of();
        }

        List<ConsistencyInvariantHintView> out = new ArrayList<>();
        List<ConsistencyParticipantHintView> participants = scenario.participants();
        if (participants != null) {
            for (ConsistencyParticipantHintView p : participants) {
                String role = p.role() != null ? p.role().toUpperCase(Locale.ROOT) : "";
                boolean coWrite = role.startsWith("REQUIRED") || "SECONDARY".equals(role);
                if (!coWrite) continue;
                if (!physicalMatchesAny(p.physicalName(), written)) continue;
                out.add(new ConsistencyInvariantHintView(
                        "ROW_EXISTS",
                        p.physicalName() + " row must be written in same handler as primary",
                        "SELECT * FROM " + p.physicalName() + " WHERE <correlationKeys>"));
            }
        }
        return out;
    }

    private List<ConsistencyInvariantHintView> deriveGate(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            List<MessagingFlowService.DataAccessView> touchpoints,
            CrossRepoTraceContext traceContext,
            String serviceId) {
        if (traceContext == null) {
            return List.of();
        }
        Set<String> participants = CrossRepoGateLinker.normalizedParticipants(
                ConsistencyHintView.fromScenario(scenario));
        if (participants.isEmpty()) {
            return List.of();
        }
        List<ConsistencyInvariantHintView> out = new ArrayList<>();
        List<MessagingFlowService.FlowGateView> gates =
                traceContext.gatesByService().getOrDefault(serviceId, List.of());
        for (MessagingFlowService.FlowGateView gate : gates) {
            if (!"BUSINESS_RULE".equalsIgnoreCase(gate.gateKind())) continue;
            if (!CrossRepoGateLinker.gateMatchesParticipant(gate.gateKey(), participants)) continue;
            out.add(new ConsistencyInvariantHintView(
                    "STATE_TRANSITION",
                    gate.gateKey() + " must satisfy " + gate.requiredValue(),
                    gate.testPrecondition()));
        }
        return out;
    }

    private static String invariantKey(String kind, String description) {
        return (kind != null ? kind : "") + "|" + (description != null ? description : "");
    }

    private static Set<String> normalizedRowTables(MessagingFlowService.DataAccessView row) {
        Set<String> tables = new LinkedHashSet<>();
        addNormalized(tables, row.tableOrEntity());
        addNormalized(tables, row.physicalName());
        addNormalized(tables, ConsistencyScenarioMatcher.TouchpointContext.entitySimpleName(row.entityFqn()));
        addNormalized(tables, ConsistencyScenarioMatcher.TouchpointContext.accessorSimpleName(row.accessorFqn()));
        return tables;
    }

    private static void addNormalized(Set<String> tables, String name) {
        if (name != null && !name.isBlank()) {
            tables.add(normalize(name));
        }
    }

    private static boolean physicalMatchesAny(String physical, Set<String> written) {
        if (physical == null || physical.isBlank()) return false;
        return written.contains(normalize(physical));
    }

    private static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }
}

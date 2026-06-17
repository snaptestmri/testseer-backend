package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ConsistencyQueryService {

    private final JdbcClient db;
    private final MessagingRulePack rulePack;

    public ConsistencyQueryService(JdbcClient db, MessagingRulePackLoader rulePackLoader) {
        this.db = db;
        this.rulePack = rulePackLoader.getRulePack();
    }

    public List<ConsistencyScenarioView> query(String serviceId, String pattern, String flowStep) {
        var spec = db.sql("""
                SELECT scenario_id, pattern, scope_kind, scope_ref, primary_store, primary_physical,
                       correlation_keys, participants, poll_strategy, invariants,
                       evidence_source, confidence, attributes
                FROM consistency_scenario_facts
                WHERE service_id = :serviceId
                """)
                .param("serviceId", serviceId);
        if (pattern != null && !pattern.isBlank()) {
            spec = spec.param("pattern", pattern);
        }
        List<ConsistencyScenarioView> rows = spec
                .query((rs, row) -> new ConsistencyScenarioView(
                        rs.getString("scenario_id"),
                        rs.getString("pattern"),
                        rs.getString("scope_kind"),
                        rs.getString("scope_ref"),
                        rs.getString("primary_store"),
                        rs.getString("primary_physical"),
                        ConsistencyHintJsonParser.parseCorrelationKeys(rs.getString("correlation_keys")),
                        ConsistencyHintJsonParser.parseParticipants(rs.getString("participants")),
                        ConsistencyHintJsonParser.parsePollStrategy(rs.getString("poll_strategy")),
                        ConsistencyHintJsonParser.parseInvariants(rs.getString("invariants")),
                        rs.getString("evidence_source"),
                        rs.getDouble("confidence"),
                        rs.getString("attributes")
                ))
                .list();

        List<ConsistencyScenarioView> merged = new ArrayList<>();
        Map<String, ConsistencyScenarioView> byId = new LinkedHashMap<>();
        for (ConsistencyScenarioView view : rows) {
            if (pattern != null && !pattern.isBlank() && !pattern.equalsIgnoreCase(view.pattern())) {
                continue;
            }
            if (flowStep != null && !flowStep.isBlank() && !matchesFlowStep(view, flowStep)) {
                continue;
            }
            byId.put(view.scenarioId(), view);
        }
        overlayRulePack(byId);
        merged.addAll(byId.values());
        if (pattern != null && !pattern.isBlank()) {
            merged = merged.stream()
                    .filter(v -> pattern.equalsIgnoreCase(v.pattern()))
                    .toList();
        }
        if (flowStep != null && !flowStep.isBlank()) {
            merged = merged.stream()
                    .filter(v -> matchesFlowStep(v, flowStep))
                    .toList();
        }
        return merged;
    }

    private void overlayRulePack(Map<String, ConsistencyScenarioView> byId) {
        if (rulePack.consistencyRules() == null) return;
        rulePack.consistencyRules().forEach((id, rule) -> {
            if (rule.flowSteps() == null || rule.flowSteps().isEmpty()) {
                byId.putIfAbsent(id, ruleToView(id, rule));
            } else {
                byId.put(id, ruleToView(id, rule));
            }
        });
    }

    private boolean matchesFlowStep(ConsistencyScenarioView view, String flowStep) {
        MessagingRulePack.ConsistencyRule rule = rulePack.consistencyRules() != null
                ? rulePack.consistencyRules().get(view.scenarioId()) : null;
        if (rule != null && rule.flowSteps() != null) {
            return rule.flowSteps().stream().anyMatch(fs -> fs.equalsIgnoreCase(flowStep));
        }
        return true;
    }

    private ConsistencyScenarioView ruleToView(String id, MessagingRulePack.ConsistencyRule rule) {
        boolean hasFlowSteps = rule.flowSteps() != null && !rule.flowSteps().isEmpty();
        return new ConsistencyScenarioView(
                id,
                rule.pattern(),
                hasFlowSteps ? "FLOW_STEP" : "RULE_PACK",
                ruleScopeRef(rule),
                rule.primaryStore(),
                rule.primaryPhysical(),
                rule.correlationKeys() != null ? rule.correlationKeys() : List.of(),
                ruleToParticipants(rule),
                ruleToPollStrategy(rule),
                ruleToInvariants(rule),
                "RULE_PACK",
                0.95,
                null
        );
    }

    private static List<ConsistencyParticipantHintView> ruleToParticipants(
            MessagingRulePack.ConsistencyRule rule) {
        if (rule.participants() == null) return List.of();
        return rule.participants().stream()
                .map(p -> new ConsistencyParticipantHintView(
                        p.storeType(), p.physicalName(), p.role(), p.via(), p.lagClass()))
                .toList();
    }

    private static ConsistencyPollStrategyView ruleToPollStrategy(
            MessagingRulePack.ConsistencyRule rule) {
        if (rule.pollStrategy() == null) return null;
        Map<String, Object> ps = rule.pollStrategy();
        @SuppressWarnings("unchecked")
        List<String> order = ps.get("order") instanceof List<?> l ? (List<String>) l : List.of();
        @SuppressWarnings("unchecked")
        List<String> notes = ps.get("notes") instanceof List<?> l ? (List<String>) l : List.of();
        return new ConsistencyPollStrategyView(
                order,
                ps.get("primaryPollHint") instanceof String s ? s : null,
                notes);
    }

    private static List<ConsistencyInvariantHintView> ruleToInvariants(
            MessagingRulePack.ConsistencyRule rule) {
        if (rule.invariants() == null) return List.of();
        return rule.invariants().stream()
                .map(inv -> new ConsistencyInvariantHintView(
                        inv.kind(), inv.description(), inv.pollHint()))
                .toList();
    }

    private static String ruleScopeRef(MessagingRulePack.ConsistencyRule rule) {
        if (rule.participants() != null && !rule.participants().isEmpty()) {
            return rule.participants().stream()
                    .map(MessagingRulePack.ConsistencyParticipantRule::physicalName)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.joining(","));
        }
        return rule.primaryPhysical() != null ? rule.primaryPhysical() : "";
    }

    public record ConsistencyScenarioView(
            String scenarioId,
            String pattern,
            String scopeKind,
            String scopeRef,
            String primaryStore,
            String primaryPhysical,
            List<String> correlationKeys,
            List<ConsistencyParticipantHintView> participants,
            ConsistencyPollStrategyView pollStrategy,
            List<ConsistencyInvariantHintView> invariants,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}
}

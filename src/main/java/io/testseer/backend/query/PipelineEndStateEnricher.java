package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** S-12 pipeline end-state hints across multi-hop traces (BL-045). */
@Component
public class PipelineEndStateEnricher {

    public List<ConsistencyHintView> forCrossRepoTrace(
            List<ConsistencyHintView> existing,
            CrossRepoTraceContext ctx,
            MessagingRulePack rulePack) {
        if (ctx == null || rulePack.consistencyRules() == null) {
            return existing;
        }
        MessagingRulePack.ConsistencyRule rule = rulePack.consistencyRules().get("stc-retry-end-state");
        if (rule == null || rule.endStateParticipants() == null || rule.endStateParticipants().isEmpty()) {
            return existing;
        }
        Set<String> touched = allTouchpointTables(ctx);
        if (!allEndStatePresent(rule, touched)) {
            return existing;
        }
        for (ConsistencyHintView hint : existing) {
            if ("stc-retry-end-state".equals(hint.scenarioId())) {
                return existing;
            }
        }
        List<ConsistencyHintView> out = new ArrayList<>(existing);
        out.add(ConsistencyHintView.fromScenario(syntheticScenario(rule)));
        return out;
    }

    private static boolean allEndStatePresent(
            MessagingRulePack.ConsistencyRule rule, Set<String> touched) {
        for (MessagingRulePack.ConsistencyParticipantRule p : rule.endStateParticipants()) {
            if (p.physicalName() == null || p.physicalName().isBlank()) {
                return false;
            }
            if (!touched.contains(normalize(p.physicalName()))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> allTouchpointTables(CrossRepoTraceContext ctx) {
        Set<String> tables = new LinkedHashSet<>();
        for (List<MessagingFlowService.DataAccessView> rows : ctx.dataAccessByService().values()) {
            for (MessagingFlowService.DataAccessView row : rows) {
                addTable(tables, row.physicalName());
                addTable(tables, row.tableOrEntity());
            }
        }
        return tables;
    }

    private static void addTable(Set<String> tables, String name) {
        if (name != null && !name.isBlank()) {
            tables.add(normalize(name));
        }
    }

    private static ConsistencyQueryService.ConsistencyScenarioView syntheticScenario(
            MessagingRulePack.ConsistencyRule rule) {
        List<ConsistencyParticipantHintView> participants = rule.endStateParticipants().stream()
                .map(p -> new ConsistencyParticipantHintView(
                        p.storeType(), p.physicalName(), p.role(), p.via(), p.lagClass()))
                .toList();
        return new ConsistencyQueryService.ConsistencyScenarioView(
                "stc-retry-end-state",
                rule.pattern() != null ? rule.pattern() : "PIPELINE_END_STATE",
                "RULE_PACK",
                "stc-retry-end-state",
                rule.primaryStore(),
                rule.primaryPhysical(),
                rule.correlationKeys(),
                participants,
                null,
                List.of(),
                "RULE_PACK",
                0.95,
                null);
    }

    private static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }
}

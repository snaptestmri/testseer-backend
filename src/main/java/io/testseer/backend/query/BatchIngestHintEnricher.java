package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** S-11 batch ingest hints from entry triggers + rule-pack anchor (BL-044). */
@Component
public class BatchIngestHintEnricher {

    private static final Set<String> BATCH_TRIGGER_KINDS = Set.of(
            "FILE_DROP_GCS", "AIRFLOW_DAG", "CRON");

    private final JdbcClient db;

    public BatchIngestHintEnricher(JdbcClient db) {
        this.db = db;
    }

    public List<ConsistencyHintView> maybeAttach(
            String orgId,
            String serviceId,
            String handlerFqn,
            Set<String> flowSteps,
            List<ConsistencyHintView> existing,
            MessagingRulePack rulePack) {
        if (rulePack.consistencyRules() == null) {
            return existing;
        }
        MessagingRulePack.ConsistencyRule rule = rulePack.consistencyRules().get("segment-parquet-batch-ingest");
        if (rule == null || !"ASYNC_BATCH_INGEST".equalsIgnoreCase(rule.pattern())) {
            return existing;
        }
        if (!flowStepOverlap(rule.flowSteps(), flowSteps)) {
            return existing;
        }
        if (!hasBatchTrigger(orgId, serviceId, handlerFqn)) {
            return existing;
        }
        for (ConsistencyHintView hint : existing) {
            if ("segment-parquet-batch-ingest".equals(hint.scenarioId())) {
                return existing;
            }
        }
        List<ConsistencyHintView> out = new ArrayList<>(existing);
        out.add(ConsistencyHintView.fromScenario(syntheticScenario(rule)));
        return out;
    }

    private boolean hasBatchTrigger(String orgId, String serviceId, String handlerFqn) {
        if (handlerFqn == null || handlerFqn.isBlank()) {
            return false;
        }
        try {
            Integer count = db.sql("""
                    SELECT COUNT(*) FROM entry_trigger_facts
                    WHERE org_id = :orgId AND service_id = :svcId
                      AND linked_handler_fqn = :handler
                      AND trigger_kind IN ('FILE_DROP_GCS', 'AIRFLOW_DAG', 'CRON')
                    """)
                    .param("orgId", orgId)
                    .param("svcId", serviceId)
                    .param("handler", handlerFqn)
                    .query(Integer.class)
                    .single();
            return count != null && count > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean flowStepOverlap(List<String> ruleSteps, Set<String> ctxSteps) {
        if (ruleSteps == null || ruleSteps.isEmpty() || ctxSteps == null || ctxSteps.isEmpty()) {
            return false;
        }
        for (String ruleStep : ruleSteps) {
            for (String ctxStep : ctxSteps) {
                if (ruleStep.equalsIgnoreCase(ctxStep)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ConsistencyQueryService.ConsistencyScenarioView syntheticScenario(
            MessagingRulePack.ConsistencyRule rule) {
        List<ConsistencyParticipantHintView> participants = rule.participants() == null
                ? List.of()
                : rule.participants().stream()
                        .map(p -> new ConsistencyParticipantHintView(
                                p.storeType(), p.physicalName(), p.role(), p.via(), p.lagClass()))
                        .toList();
        ConsistencyPollStrategyView poll = null;
        if (rule.pollStrategy() != null) {
            @SuppressWarnings("unchecked")
            List<String> order = rule.pollStrategy().get("order") instanceof List<?> l
                    ? (List<String>) l : null;
            @SuppressWarnings("unchecked")
            List<String> notes = rule.pollStrategy().get("notes") instanceof List<?> n
                    ? (List<String>) n : null;
            poll = new ConsistencyPollStrategyView(
                    order,
                    rule.pollStrategy().get("primaryPollHint") != null
                            ? String.valueOf(rule.pollStrategy().get("primaryPollHint")) : null,
                    notes);
        }
        return new ConsistencyQueryService.ConsistencyScenarioView(
                "segment-parquet-batch-ingest",
                rule.pattern(),
                "RULE_PACK",
                "segment-parquet-batch-ingest",
                rule.primaryStore(),
                rule.primaryPhysical(),
                rule.correlationKeys(),
                participants,
                poll,
                List.of(),
                "RULE_PACK",
                0.95,
                null);
    }
}

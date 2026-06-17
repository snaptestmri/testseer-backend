package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ValidationHintBuilder {

    private final MessagingRulePack rulePack;

    public ValidationHintBuilder(MessagingRulePackLoader rulePackLoader) {
        this.rulePack = rulePackLoader.getRulePack();
    }

    public List<FactBatch.ValidationHintFact> build(
            List<FactBatch.PubSubResourceFact> pubsub,
            List<FactBatch.DataAccessFact> dataAccess,
            List<FactBatch.FlowGateFact> gates,
            String envLane) {

        List<FactBatch.ValidationHintFact> hints = new ArrayList<>();

        for (FactBatch.PubSubResourceFact p : pubsub) {
            if (p.linkedClassFqn() == null) continue;
            String step = flowStep(p);
            if (p.workloadName() != null) {
                hints.add(hint(step, "K8S_WORKLOAD", p.workloadName(), p.linkedClassFqn(), p.envLane()));
            }
            if ("PUBLISH".equals(p.role())) {
                hints.add(hint(step, "TOPIC", p.shortId(), p.linkedClassFqn(), p.envLane()));
            } else {
                hints.add(hint(step, "SUBSCRIPTION", p.shortId(), p.linkedClassFqn(), p.envLane()));
            }
        }

        for (FactBatch.DataAccessFact d : dataAccess) {
            String step = flowStepFromClass(d.handlerClassFqn());
            MessagingRulePack.DbTableHintRule tableHint =
                    rulePack.dbTableHints().get(normalizeTable(d.tableOrEntity()));
            if (tableHint != null && tableHint.hintValue() != null) {
                hints.add(hint(step, "DB_POLL", tableHint.hintValue(), d.handlerClassFqn(), envLane));
            }
        }

        for (FactBatch.FlowGateFact g : gates) {
            if (g.testPrecondition() != null) {
                hints.add(hint(
                        g.guardedFlowStep() != null ? g.guardedFlowStep() : "PRECONDITION",
                        "GATE", g.testPrecondition(), g.guardedSymbolFqn(), g.envLane()
                ));
            }
        }

        return hints;
    }

    private FactBatch.ValidationHintFact hint(
            String step, String kind, String value, String symbol, String env) {
        return new FactBatch.ValidationHintFact(
                step != null ? step : "GENERAL", kind, value, symbol,
                env != null ? env : "unknown"
        );
    }

    private String flowStep(FactBatch.PubSubResourceFact p) {
        if (p.shortId() != null) {
            for (MessagingRulePack.TopicFlowStepRule rule : rulePack.topicFlowSteps()) {
                if (rule.match() != null && p.shortId().contains(rule.match())) {
                    return rule.flowStep();
                }
            }
        }
        return "GENERAL";
    }

    private String flowStepFromClass(String fqn) {
        if (fqn == null) return "GENERAL";
        for (MessagingRulePack.ClassFlowStepRule rule : rulePack.classFlowSteps()) {
            if (rule.match() != null && fqn.contains(rule.match())) {
                return rule.flowStep();
            }
        }
        return "GENERAL";
    }

    private static String normalizeTable(String tableOrEntity) {
        return tableOrEntity == null ? "" : tableOrEntity.toLowerCase(Locale.ROOT);
    }
}

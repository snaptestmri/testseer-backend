package io.testseer.backend.ingestion.consistency;

import io.testseer.backend.config.ConsistencyRuleSupport;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CON-02: orchestrate handler + mirror scenario extraction at index time. */
@Service
public class ConsistencyFactOrchestrator {

    private final ConsistencyScenarioExtractor scenarioExtractor;
    private final MessagingRulePack rulePack;

    public ConsistencyFactOrchestrator(
            ConsistencyScenarioExtractor scenarioExtractor,
            MessagingRulePackLoader rulePackLoader) {
        this.scenarioExtractor = scenarioExtractor;
        this.rulePack = rulePackLoader.getRulePack();
    }

    public List<FactBatch.ConsistencyScenarioFact> buildFromServiceIndex(
            List<FactBatch.DataAccessFact> dataAccessFacts) {
        List<FactBatch.ConsistencyScenarioFact> inferred = new ArrayList<>();
        inferred.addAll(scenarioExtractor.fromHandlerWrites(dataAccessFacts));
        inferred.addAll(scenarioExtractor.fromHandlerReads(dataAccessFacts));
        return mergeWithRulePack(inferred);
    }

    public List<FactBatch.ConsistencyScenarioFact> buildFromLibraryIndex(
            List<FactBatch.DataObjectFact> dataObjectFacts) {
        List<FactBatch.ConsistencyScenarioFact> inferred =
                scenarioExtractor.fromEntityMirrors(dataObjectFacts);
        return mergeWithRulePack(inferred);
    }

    private List<FactBatch.ConsistencyScenarioFact> mergeWithRulePack(
            List<FactBatch.ConsistencyScenarioFact> inferred) {
        if (rulePack.consistencyRules() == null || rulePack.consistencyRules().isEmpty()) {
            return inferred;
        }
        Map<String, FactBatch.ConsistencyScenarioFact> byId = new LinkedHashMap<>();
        for (FactBatch.ConsistencyScenarioFact fact : inferred) {
            byId.put(fact.scenarioId(), fact);
        }
        for (Map.Entry<String, MessagingRulePack.ConsistencyRule> entry : rulePack.consistencyRules().entrySet()) {
            MessagingRulePack.ConsistencyRule rule = entry.getValue();
            byId.put(entry.getKey(), rulePackToFact(entry.getKey(), rule));
        }
        return List.copyOf(byId.values());
    }

    private FactBatch.ConsistencyScenarioFact rulePackToFact(
            String scenarioId, MessagingRulePack.ConsistencyRule rule) {
        return new FactBatch.ConsistencyScenarioFact(
                scenarioId,
                rule.pattern(),
                "HANDLER",
                scenarioId,
                rule.primaryStore(),
                rule.primaryPhysical(),
                ConsistencyRuleSupport.correlationKeysJson(rule),
                ConsistencyRuleSupport.participantsJson(rule),
                ConsistencyRuleSupport.pollStrategyJson(rule),
                ConsistencyRuleSupport.invariantsJson(rule),
                "RULE_PACK",
                0.95,
                null
        );
    }
}

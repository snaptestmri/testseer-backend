package io.testseer.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class TriggerRulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(TriggerRulePackLoader.class);

    private final TriggerRulePack rulePack;

    public TriggerRulePackLoader(
            @Value("${testseer.triggers.rule-pack-path:file:../config/rule-packs/quotient-triggers.yml}") Resource rulePackPath) {
        this.rulePack = load(rulePackPath);
    }

    public TriggerRulePack getRulePack() {
        return rulePack;
    }

    @SuppressWarnings("unchecked")
    private TriggerRulePack load(Resource resource) {
        try (InputStream in = openStream(resource)) {
            if (in == null) {
                log.info("Trigger rule pack not found at {}; using heuristics only", resource);
                return TriggerRulePack.empty();
            }
            Map<String, Object> raw = new Yaml().load(in);
            if (raw == null) {
                return TriggerRulePack.empty();
            }
            List<TriggerRulePack.InboundRestTriggerRule> rules = list(raw.get("inboundRestTriggers")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new TriggerRulePack.InboundRestTriggerRule(
                            string(m.get("match")),
                            string(m.get("pathPrefix")),
                            string(m.get("triggerKind")),
                            string(m.get("actor")),
                            string(m.get("boundary")),
                            string(m.get("flowStep")),
                            string(m.get("envLane"))
                    ))
                    .toList();
            List<TriggerRulePack.AirflowTriggerRule> airflowRules = list(raw.get("airflowRules")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new TriggerRulePack.AirflowTriggerRule(
                            string(m.get("match")),
                            string(m.get("dagId")),
                            string(m.get("taskId")),
                            string(m.get("flowStep")),
                            string(m.get("linkedServiceModule")),
                            string(m.get("actor")),
                            string(m.get("boundary")),
                            string(m.get("envLane"))
                    ))
                    .toList();
            List<TriggerRulePack.CronHandlerLinkRule> cronLinks = list(raw.get("cronHandlerLinks")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new TriggerRulePack.CronHandlerLinkRule(
                            string(m.get("cronJobName")),
                            string(m.get("classFqn")),
                            string(m.get("method"))))
                    .filter(r -> r.cronJobName() != null && r.classFqn() != null)
                    .toList();
            return new TriggerRulePack(rules, airflowRules, cronLinks);
        } catch (Exception ex) {
            log.warn("Failed to load trigger rule pack from {}: {}", resource, ex.getMessage());
            return TriggerRulePack.empty();
        }
    }

    private InputStream openStream(Resource resource) throws IOException {
        if (resource.exists()) {
            return resource.getInputStream();
        }
        String path = System.getenv("TESTSEER_TRIGGER_RULE_PACK");
        if (path != null && !path.isBlank() && Files.isRegularFile(Path.of(path))) {
            return Files.newInputStream(Path.of(path));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List<?> list) return (List<Object>) list;
        return List.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

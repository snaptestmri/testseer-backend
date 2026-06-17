package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AirflowDagTriggerExtractor {

    private static final Pattern DAG_ID = Pattern.compile(
            "DAG\\s*\\(\\s*['\"]([^'\"]+)['\"]|dag_id\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_ID = Pattern.compile(
            "task_id\\s*=\\s*['\"]([^'\"]+)['\"]|@task(?:\\([^)]*\\))?\\s*\\n\\s*def\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            Map<String, String> contentByPath,
            TriggerRulePack rulePack,
            String defaultEnvLane) {
        return extract(contentByPath, rulePack, defaultEnvLane, null, null);
    }

    public List<FactBatch.EntryTriggerFact> extract(
            Map<String, String> contentByPath,
            TriggerRulePack rulePack,
            String defaultEnvLane,
            String serviceName,
            String repo) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String envLane = defaultEnvLane != null ? defaultEnvLane : "unknown";

        if (rulePack != null && rulePack.airflowRules() != null) {
            for (TriggerRulePack.AirflowTriggerRule rule : rulePack.airflowRules()) {
                if (!matchesLinkedService(rule.linkedServiceModule(), serviceName, repo)) continue;
                String dagId = rule.dagId();
                String taskId = rule.taskId();
                if (dagId == null || taskId == null) continue;
                String triggerId = "airflow:" + dagId + ":" + taskId;
                if (!seen.add(triggerId)) continue;
                results.add(buildFact(
                        triggerId, dagId, taskId, rule.flowStep(),
                        rule.actor(), rule.boundary(),
                        rule.envLane() != null ? rule.envLane() : envLane,
                        rule.linkedServiceModule(), "quotient-triggers.yml",
                        "RULE_PACK", 0.90, rule.match()));
            }
        }

        if (contentByPath != null) {
            for (Map.Entry<String, String> entry : contentByPath.entrySet()) {
                String path = entry.getKey();
                if (path == null || !looksLikeDagFile(path)) continue;
                String content = entry.getValue();
                if (content == null || content.isBlank()) continue;

                String dagId = extractDagId(content);
                if (dagId == null) continue;

                Matcher taskMatcher = TASK_ID.matcher(content);
                while (taskMatcher.find()) {
                    String taskId = taskMatcher.group(1) != null ? taskMatcher.group(1) : taskMatcher.group(2);
                    if (taskId == null) continue;
                    String triggerId = "airflow:" + dagId + ":" + taskId;
                    if (!seen.add(triggerId)) continue;
                    results.add(buildFact(
                            triggerId, dagId, taskId, null, "airflow", "INTERNAL",
                            envLane, null, path, "AIRFLOW_DAG_PARSE", 0.78, null));
                }
            }
        }
        return results;
    }

    private FactBatch.EntryTriggerFact buildFact(
            String triggerId, String dagId, String taskId, String flowStep,
            String actor, String boundary, String envLane, String linkedModule,
            String sourceRef, String evidence, double confidence, String ruleMatch) {
        String path = "/dag/" + dagId + "/task/" + taskId;
        return new FactBatch.EntryTriggerFact(
                triggerId,
                "AIRFLOW_DAG",
                "INBOUND",
                envLane,
                actor != null ? actor : "airflow",
                boundary != null ? boundary : "INTERNAL",
                null,
                path,
                null,
                null,
                flowStep,
                sourceRef,
                evidence,
                confidence,
                attributes(dagId, taskId, linkedModule, ruleMatch)
        );
    }

    private String attributes(String dagId, String taskId, String linkedModule, String ruleMatch) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dagId", dagId);
        map.put("taskId", taskId);
        if (linkedModule != null) map.put("linkedServiceModule", linkedModule);
        if (ruleMatch != null) map.put("ruleMatch", ruleMatch);
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String extractDagId(String content) {
        Matcher m = DAG_ID.matcher(content);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static boolean looksLikeDagFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/dags/") || lower.startsWith("dags/")
                || lower.endsWith("_dag.py") || lower.contains("airflow");
    }

    static boolean matchesLinkedService(String linkedServiceModule, String serviceName, String repo) {
        if (linkedServiceModule == null || linkedServiceModule.isBlank()) return false;
        if (serviceName != null && linkedServiceModule.equals(serviceName)) return true;
        return repo != null && linkedServiceModule.equals(repo);
    }
}

package io.testseer.backend.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TRG-14+ extension: when a Pub/Sub hop has no subscriber, link indexed BQ DLQ paths and
 * CRON_K8S / BATCH_LAUNCHER entry triggers (plus rule-pack disambiguation).
 */
@Service
public class TerminalHopEnricher {

    private static final Set<String> SUBSCRIBER_GAP_TYPES = Set.of(
            "NO_SUBSCRIBER", "NO_SUBSCRIBER_INDEX_GAP", "TERMINAL_EXTERNAL", "MANIFEST_ONLY_PUBLISHER");

    private final JdbcClient db;
    private final MessagingRulePack rulePack;
    private final ObjectMapper mapper = new ObjectMapper();

    public TerminalHopEnricher(JdbcClient db, MessagingRulePackLoader rulePackLoader) {
        this.db = db;
        this.rulePack = rulePackLoader.getRulePack();
    }

    public Result enrich(String orgId, String envLane, List<MessagingFlowService.CrossRepoHop> hops,
                         List<MessagingFlowService.FlowGap> gaps) {
        if (hops == null || hops.isEmpty()) {
            return new Result(hops, gaps);
        }
        List<AsyncRetryPathRow> dlqPaths = loadDlqPaths(orgId, envLane);
        List<CronTriggerRow> cronTriggers = loadCronTriggers(orgId, envLane);
        List<MessagingRulePack.TerminalRetryPathRule> rules = rulePack.terminalRetryPaths() != null
                ? rulePack.terminalRetryPaths() : List.of();

        List<MessagingFlowService.CrossRepoHop> enrichedHops = new ArrayList<>();
        Set<String> resolvedTopics = new LinkedHashSet<>();

        for (MessagingFlowService.CrossRepoHop hop : hops) {
            if (hop.subscribers() != null && !hop.subscribers().isEmpty()) {
                enrichedHops.add(hop);
                continue;
            }
            List<MessagingFlowService.TerminalContinuationView> continuations =
                    resolveContinuations(hop.topicShortId(), dlqPaths, cronTriggers, rules);
            if (!continuations.isEmpty()) {
                resolvedTopics.add(hop.topicShortId());
            }
            enrichedHops.add(new MessagingFlowService.CrossRepoHop(
                    hop.order(), hop.topicShortId(), hop.transport(), hop.publishers(), hop.subscribers(),
                    continuations));
        }

        List<MessagingFlowService.FlowGap> filteredGaps = gaps == null ? List.of() : gaps.stream()
                .filter(gap -> !(SUBSCRIBER_GAP_TYPES.contains(gap.gapType())
                        && resolvedTopics.stream().anyMatch(topic -> gapMatchesTopic(gap, topic))))
                .toList();

        List<MessagingFlowService.FlowGap> augmentedGaps = new ArrayList<>(filteredGaps);
        for (String topic : resolvedTopics) {
            augmentedGaps.add(new MessagingFlowService.FlowGap(
                    "TERMINAL_BATCH_RETRY",
                    "Topic " + topic + " has no Pub/Sub subscriber; continuations via BQ DLQ + batch/CronJob"));
        }

        return new Result(enrichedHops, augmentedGaps);
    }

    private static boolean gapMatchesTopic(MessagingFlowService.FlowGap gap, String topic) {
        if (gap.topicShortId() != null && topic.equals(gap.topicShortId())) {
            return true;
        }
        return gap.description() != null && gap.description().contains(topic);
    }

    private List<MessagingFlowService.TerminalContinuationView> resolveContinuations(
            String topic,
            List<AsyncRetryPathRow> dlqPaths,
            List<CronTriggerRow> cronTriggers,
            List<MessagingRulePack.TerminalRetryPathRule> rules) {
        List<MessagingFlowService.TerminalContinuationView> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<MessagingRulePack.TerminalRetryPathRule> matchingRules = rules.stream()
                .filter(rule -> topicContains(topic, rule.topicMatch()))
                .toList();

        if (!matchingRules.isEmpty()) {
            for (MessagingRulePack.TerminalRetryPathRule rule : matchingRules) {
                AsyncRetryPathRow dlq = dlqPaths.stream()
                        .filter(row -> tableMatches(row.bqTable(), rule.bqTableSuffix()))
                        .findFirst()
                        .orElse(null);
                CronTriggerRow cron = findCron(cronTriggers, rule.cronJobName());
                String key = rule.cronJobName() + "|" + rule.bqTableSuffix();
                if (!seen.add(key)) continue;
                results.add(buildContinuation(rule.cronJobName(), rule.partnerVariant(),
                        dlq, cron, "RULE_PACK", rule.note()));
            }
            return results;
        }

        for (AsyncRetryPathRow dlq : dlqPaths) {
            if (dlq.linkedTopic() != null && !topic.equalsIgnoreCase(dlq.linkedTopic())) continue;
            if (dlq.linkedTopic() == null && !topicStemMatches(topic, dlq.bqTable())) continue;
            CronTriggerRow cron = inferCronForModule(cronTriggers, dlq.moduleName());
            String key = dlq.bqDataset() + "|" + dlq.bqTable();
            if (!seen.add(key)) continue;
            results.add(buildContinuation(
                    cron != null ? cron.cronJobName() : null,
                    null,
                    dlq,
                    cron,
                    "INDEXED_DLQ",
                    "Retry via BigQuery DLQ table"));
        }
        return results;
    }

    private static MessagingFlowService.TerminalContinuationView buildContinuation(
            String cronJobName,
            String partnerVariant,
            AsyncRetryPathRow dlq,
            CronTriggerRow cron,
            String evidenceSource,
            String note) {
        return new MessagingFlowService.TerminalContinuationView(
                cron != null ? cron.triggerKind() : "BQ_DLQ",
                cronJobName != null ? cronJobName : (cron != null ? cron.cronJobName() : null),
                dlq != null ? dlq.bqDataset() : null,
                dlq != null ? dlq.bqTable() : null,
                partnerVariant,
                cron != null ? cron.serviceId() : (dlq != null ? dlq.serviceId() : null),
                cron != null ? cron.repo() : (dlq != null ? dlq.repo() : null),
                cron != null ? cron.schedule() : null,
                evidenceSource,
                note
        );
    }

    private static CronTriggerRow findCron(List<CronTriggerRow> cronTriggers, String cronJobName) {
        if (cronJobName == null) return null;
        String needle = cronJobName.toLowerCase(Locale.ROOT);
        return cronTriggers.stream()
                .filter(row -> row.cronJobName().toLowerCase(Locale.ROOT).contains(needle))
                .findFirst()
                .orElse(null);
    }

    private static CronTriggerRow inferCronForModule(List<CronTriggerRow> cronTriggers, String moduleName) {
        if (moduleName == null) return null;
        String stem = moduleName.replace("-job", "").toLowerCase(Locale.ROOT);
        return cronTriggers.stream()
                .filter(row -> row.cronJobName().toLowerCase(Locale.ROOT).contains(stem))
                .findFirst()
                .orElse(null);
    }

    private static boolean topicContains(String topic, String fragment) {
        if (topic == null || fragment == null) return false;
        return topic.toUpperCase(Locale.ROOT).contains(fragment.toUpperCase(Locale.ROOT));
    }

    private static boolean tableMatches(String table, String suffix) {
        if (table == null || suffix == null) return false;
        return table.toUpperCase(Locale.ROOT).contains(suffix.toUpperCase(Locale.ROOT));
    }

    private static boolean topicStemMatches(String topic, String table) {
        if (topic == null || table == null) return false;
        String topicStem = topic.replaceAll("^[A-Z0-9_]+\\.", "")
                .replace("_RETRY", "")
                .replace("_T.", "");
        return table.toUpperCase(Locale.ROOT).contains(topicStem.toUpperCase(Locale.ROOT));
    }

    private List<AsyncRetryPathRow> loadDlqPaths(String orgId, String envLane) {
        return db.sql("""
                        SELECT service_id, repo, module_name, linked_topic, bq_dataset, bq_table
                        FROM async_retry_path_facts
                        WHERE org_id = :orgId AND env_lane = :env
                        """)
                .param("orgId", orgId)
                .param("env", envLane)
                .query((rs, rowNum) -> new AsyncRetryPathRow(
                        rs.getString("service_id"),
                        rs.getString("repo"),
                        rs.getString("module_name"),
                        rs.getString("linked_topic"),
                        rs.getString("bq_dataset"),
                        rs.getString("bq_table")))
                .list();
    }

    private List<CronTriggerRow> loadCronTriggers(String orgId, String envLane) {
        return db.sql("""
                        SELECT service_id, repo, trigger_kind, path_pattern, attributes
                        FROM entry_trigger_facts
                        WHERE org_id = :orgId AND env_lane = :env
                          AND trigger_kind IN ('CRON_K8S', 'BATCH_LAUNCHER')
                        """)
                .param("orgId", orgId)
                .param("env", envLane)
                .query((rs, rowNum) -> new CronTriggerRow(
                        rs.getString("service_id"),
                        rs.getString("repo"),
                        rs.getString("trigger_kind"),
                        cronJobName(rs.getString("path_pattern"), rs.getString("attributes")),
                        scheduleFromAttributes(rs.getString("attributes"))))
                .list();
    }

    private String cronJobName(String pathPattern, String attributesJson) {
        if (pathPattern != null && pathPattern.startsWith("/cronjob/")) {
            return pathPattern.substring("/cronjob/".length());
        }
        Map<String, Object> attrs = parseAttributes(attributesJson);
        Object cronJob = attrs.get("cronJob");
        return cronJob != null ? String.valueOf(cronJob) : pathPattern;
    }

    private String scheduleFromAttributes(String attributesJson) {
        Map<String, Object> attrs = parseAttributes(attributesJson);
        Object schedule = attrs.get("schedule");
        return schedule != null ? String.valueOf(schedule) : null;
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public record Result(
            List<MessagingFlowService.CrossRepoHop> hops,
            List<MessagingFlowService.FlowGap> gaps
    ) {}

    private record AsyncRetryPathRow(
            String serviceId, String repo, String moduleName,
            String linkedTopic, String bqDataset, String bqTable
    ) {}

    private record CronTriggerRow(
            String serviceId, String repo, String triggerKind,
            String cronJobName, String schedule
    ) {}
}

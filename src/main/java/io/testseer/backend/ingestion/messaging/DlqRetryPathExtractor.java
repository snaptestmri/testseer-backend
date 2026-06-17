package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Indexes BigQuery DLQ tables from retry-job application YAML for terminal-hop linking. */
@Component
public class DlqRetryPathExtractor {

    private static final Pattern DATASET =
            Pattern.compile("dataset-name:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE =
            Pattern.compile("table-name:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETRY_TOPIC =
            Pattern.compile("(?:activate-retry|redeem-retry):\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    public List<FactBatch.AsyncRetryPathFact> extract(List<YamlPubSubExtractor.ConfigFile> configFiles) {
        List<FactBatch.AsyncRetryPathFact> results = new ArrayList<>();
        if (configFiles == null) return results;

        Set<String> seen = new LinkedHashSet<>();
        for (YamlPubSubExtractor.ConfigFile file : configFiles) {
            if (file.content() == null) continue;
            String lowerPath = file.path().toLowerCase(Locale.ROOT);
            if (!lowerPath.contains("retry-job") && !lowerPath.contains("retry_job")) continue;

            Matcher datasetMatcher = DATASET.matcher(file.content());
            Matcher tableMatcher = TABLE.matcher(file.content());
            if (!datasetMatcher.find() || !tableMatcher.find()) continue;

            String dataset = datasetMatcher.group(1).trim();
            String table = tableMatcher.group(1).trim();
            if (!dataset.toUpperCase(Locale.ROOT).contains("DLQ")) continue;

            String envLane = inferEnvLane(file.path());
            String moduleName = inferModuleName(file.path());
            String linkedTopic = extractRetryTopic(file.content());
            String dedupe = envLane + "|" + dataset + "|" + table + "|" + moduleName;
            if (!seen.add(dedupe)) continue;

            results.add(new FactBatch.AsyncRetryPathFact(
                    envLane,
                    moduleName,
                    linkedTopic,
                    dataset,
                    table,
                    file.path(),
                    "YAML_DLQ_RETRY",
                    0.90,
                    null
            ));
        }
        return results;
    }

    private static String extractRetryTopic(String content) {
        Matcher m = RETRY_TOPIC.matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String inferEnvLane(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("application-pdn") || lower.contains("/pdn/") || lower.contains("/pn1/")) {
            return "pdn";
        }
        if (lower.contains("application-qa") || lower.contains("/qa/")) return "qa";
        if (lower.contains("application-prod") || lower.contains("/prod")) return "prod";
        if (lower.contains("application-dev") || lower.contains("/dev/")) return "dev";
        return "unknown";
    }

    private static String inferModuleName(String path) {
        String normalized = path.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (segment.contains("retry-job") || segment.contains("retry_job")) {
                return segment;
            }
        }
        return null;
    }
}

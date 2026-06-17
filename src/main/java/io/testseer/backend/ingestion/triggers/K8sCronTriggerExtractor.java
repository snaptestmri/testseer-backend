package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class K8sCronTriggerExtractor {

    private static final Pattern CRONJOB = Pattern.compile(
            "kind:\\s*CronJob[\\s\\S]*?metadata:\\s*[\\s\\S]*?name:\\s*([\\w-]+)[\\s\\S]*?"
                    + "schedule:\\s*[\"']?([^\"'\\n]+)",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<YamlPubSubExtractor.ConfigFile> yamlFiles, String defaultEnvLane) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (yamlFiles == null) return results;

        for (YamlPubSubExtractor.ConfigFile file : yamlFiles) {
            if (file.content() == null) continue;
            String lowerPath = file.path().toLowerCase(Locale.ROOT);
            if (!lowerPath.contains("cronjob") && !file.content().contains("kind: CronJob")) continue;

            Matcher m = CRONJOB.matcher(file.content());
            while (m.find()) {
                String name = m.group(1);
                String schedule = m.group(2).trim();
                String triggerId = "k8s-cron:" + name;
                if (!seen.add(triggerId)) continue;

                results.add(new FactBatch.EntryTriggerFact(
                        triggerId,
                        "CRON_K8S",
                        "INBOUND",
                        defaultEnvLane != null ? defaultEnvLane : "unknown",
                        "kubernetes",
                        "INTERNAL",
                        null,
                        "/cronjob/" + name,
                        null,
                        null,
                        null,
                        file.path(),
                        "K8S_MANIFEST",
                        0.90,
                        attributes(name, schedule)
                ));
            }
        }
        return results;
    }

    private String attributes(String name, String schedule) {
        try {
            return mapper.writeValueAsString(Map.of("cronJob", name, "schedule", schedule));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}

package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
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
public class SpringCronTriggerExtractor {

    private static final Pattern SCHEDULED =
            Pattern.compile("@Scheduled\\s*\\(\\s*(?:cron\\s*=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern METHOD =
            Pattern.compile("(public|protected)\\s+[\\w<>,\\[\\].\\s]+\\s+(\\w+)\\s*\\(");

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models, Map<String, String> contentByPath, String defaultEnvLane) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null) continue;
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (content == null) continue;
            Matcher m = SCHEDULED.matcher(content);
            while (m.find()) {
                String cron = m.group(1);
                String method = enclosingMethod(content, m.start());
                String triggerId = "cron:" + sanitize(model.classFqn()) + ":" + sanitize(method);
                String key = triggerId + "|" + cron;
                if (!seen.add(key)) continue;

                results.add(new FactBatch.EntryTriggerFact(
                        triggerId,
                        "CRON_SPRING",
                        "INBOUND",
                        defaultEnvLane != null ? defaultEnvLane : "unknown",
                        "scheduler",
                        "INTERNAL",
                        null,
                        cron,
                        model.classFqn(),
                        method,
                        null,
                        model.filePath(),
                        "JAVA_ANNOTATION",
                        0.88,
                        attributes(model.classFqn(), method, cron)
                ));
            }
        }
        return results;
    }

    private static String enclosingMethod(String content, int annoIndex) {
        Matcher m = METHOD.matcher(content);
        String last = "run";
        while (m.find()) {
            if (m.start() < annoIndex) last = m.group(2);
        }
        return last;
    }

    private String attributes(String classFqn, String method, String cron) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "classFqn", classFqn,
                    "method", method,
                    "cron", cron
            ));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-").toLowerCase(Locale.ROOT);
    }
}

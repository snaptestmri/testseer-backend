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

/** Detects Spring Batch / one-shot retry jobs that implement CommandLineRunner. */
@Component
public class BatchLauncherTriggerExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models, Map<String, String> contentByPath, String defaultEnvLane) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (models == null) return results;

        for (ParsedModel model : models) {
            if (model.classFqn() == null) continue;
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (content == null) continue;
            if (!content.contains("CommandLineRunner")) continue;
            if (!content.contains("SpringApplication.run")) continue;

            String simple = simpleName(model.classFqn());
            if (!simple.toLowerCase(Locale.ROOT).contains("retry")) continue;

            String triggerId = "batch-launcher:" + model.classFqn();
            if (!seen.add(triggerId)) continue;

            results.add(new FactBatch.EntryTriggerFact(
                    triggerId,
                    "BATCH_LAUNCHER",
                    "INBOUND",
                    defaultEnvLane != null ? defaultEnvLane : "unknown",
                    "batch",
                    "INTERNAL",
                    null,
                    "/batch/" + simple,
                    model.classFqn(),
                    "run",
                    null,
                    model.filePath(),
                    "JAVA_COMMAND_LINE_RUNNER",
                    0.88,
                    attributes(model.classFqn())
            ));
        }
        return results;
    }

    private String attributes(String classFqn) {
        try {
            return mapper.writeValueAsString(Map.of("launcherClass", classFqn));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }
}

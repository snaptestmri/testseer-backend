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
public class GcsTriggerExtractor {

    private static final Pattern GS_URI = Pattern.compile("gs://([a-zA-Z0-9._-]+)(?:/([a-zA-Z0-9._*/-]+))?");

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models, Map<String, String> contentByPath, String defaultEnvLane) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null) continue;
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (content == null) continue;
            Matcher m = GS_URI.matcher(content);
            while (m.find()) {
                String bucket = m.group(1);
                String prefix = m.group(2) != null ? m.group(2) : "";
                String path = "gs://" + bucket + (prefix.isBlank() ? "" : "/" + prefix);
                String triggerId = "gcs:" + bucket + ":" + sanitize(model.classFqn());
                if (!seen.add(triggerId + "|" + path)) continue;

                results.add(new FactBatch.EntryTriggerFact(
                        triggerId,
                        "FILE_DROP_GCS",
                        "INBOUND",
                        defaultEnvLane != null ? defaultEnvLane : "unknown",
                        "gcs",
                        "EXTERNAL",
                        null,
                        path,
                        model.classFqn(),
                        null,
                        null,
                        model.filePath(),
                        "JAVA_HEURISTIC",
                        0.82,
                        attributes(model.classFqn(), bucket, prefix)
                ));
            }
        }
        return results;
    }

    private String attributes(String classFqn, String bucket, String prefix) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "classFqn", classFqn,
                    "bucket", bucket,
                    "prefix", prefix
            ));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-").toLowerCase(Locale.ROOT);
    }
}

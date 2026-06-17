package io.testseer.backend.ingestion.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class JsonSchemaSummaryExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public record SchemaSummary(
            String schemaId,
            String title,
            String type,
            List<String> topLevelFields,
            List<String> requiredFields,
            List<String> nestedFieldPaths
    ) {}

    public SchemaSummary summarize(String schemaId, Map<String, Object> schema) {
        return summarize(schemaId, schema, List.of());
    }

    public SchemaSummary summarize(String schemaId, Map<String, Object> schema, List<String> nestedFieldPaths) {
        if (schema == null) {
            return new SchemaSummary(schemaId, null, null, List.of(), List.of(), List.of());
        }
        String title = string(schema.get("title"));
        String type = string(schema.get("type"));
        List<String> fields = propertyNames(schema.get("properties"));
        List<String> required = requiredNames(schema.get("required"));
        return new SchemaSummary(schemaId, title, type, fields, required,
                nestedFieldPaths != null ? nestedFieldPaths : List.of());
    }

    public String fieldsJson(List<String> fields) {
        return toJson(fields);
    }

    @SuppressWarnings("unchecked")
    private static List<String> propertyNames(Object properties) {
        if (!(properties instanceof Map<?, ?> map)) return List.of();
        List<String> names = new ArrayList<>();
        for (Object key : map.keySet()) {
            names.add(String.valueOf(key));
        }
        return List.copyOf(names);
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredNames(Object required) {
        if (!(required instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }
}

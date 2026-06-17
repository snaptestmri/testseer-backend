package io.testseer.backend.ingestion.contract;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class JsonSchemaNestedWalker {

    private static final int DEFAULT_MAX_DEPTH = 12;

    public List<String> walk(String schemaId, Map<String, Object> schema, Map<String, Map<String, Object>> schemaIndex) {
        if (schema == null) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        walkNode(schemaId, schema, schemaIndex, "", 0, DEFAULT_MAX_DEPTH, new LinkedHashSet<>(), paths);
        return List.copyOf(paths);
    }

    @SuppressWarnings("unchecked")
    private void walkNode(
            String schemaId,
            Map<String, Object> schema,
            Map<String, Map<String, Object>> schemaIndex,
            String prefix,
            int depth,
            int maxDepth,
            Set<String> visitedRefs,
            Set<String> paths) {

        if (schema == null || depth > maxDepth) {
            return;
        }

        String ref = string(schema.get("$ref"));
        if (ref != null) {
            String resolvedId = OpenApiSpecParser.normalizeSchemaId(ref);
            if (!visitedRefs.add(resolvedId)) {
                return;
            }
            Map<String, Object> resolved = schemaIndex.get(resolvedId);
            if (resolved != null) {
                walkNode(resolvedId, resolved, schemaIndex, prefix, depth, maxDepth, visitedRefs, paths);
            }
            return;
        }

        String type = string(schema.get("type"));
        if ("array".equals(type)) {
            String arrayPrefix = prefix.isEmpty() ? "[]" : prefix + "[]";
            if (!prefix.isEmpty()) {
                paths.add(prefix);
            }
            Map<String, Object> items = asMap(schema.get("items"));
            if (items != null) {
                walkNode(schemaId, items, schemaIndex, arrayPrefix, depth + 1, maxDepth, visitedRefs, paths);
            }
            return;
        }

        Map<String, Object> properties = asMap(schema.get("properties"));
        if (properties != null && !properties.isEmpty()) {
            if (!prefix.isEmpty()) {
                paths.add(prefix);
            }
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String field = entry.getKey();
                String childPrefix = prefix.isEmpty() ? field : prefix + "." + field;
                Map<String, Object> childSchema = asMap(entry.getValue());
                if (childSchema != null) {
                    walkNode(schemaId, childSchema, schemaIndex, childPrefix, depth + 1, maxDepth, visitedRefs, paths);
                }
            }
            return;
        }

        if (!prefix.isEmpty()) {
            paths.add(prefix);
        } else if (type != null) {
            paths.add(type.toLowerCase(Locale.ROOT));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

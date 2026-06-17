package io.testseer.backend.ingestion.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OpenApiSpecParser {

    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "patch", "delete", "head", "options");

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaSummaryExtractor schemaSummaryExtractor;
    private final JsonSchemaNestedWalker nestedWalker;

    public OpenApiSpecParser(
            JsonSchemaSummaryExtractor schemaSummaryExtractor,
            JsonSchemaNestedWalker nestedWalker) {
        this.schemaSummaryExtractor = schemaSummaryExtractor;
        this.nestedWalker = nestedWalker;
    }

    public record ParsedOperation(
            String operationId,
            String specDomain,
            String specFile,
            String openapiVersion,
            String operationIdOpenapi,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String summary,
            List<String> tags,
            String requestSchemaRef,
            String responseSchemaRef,
            List<String> requestFieldSummary,
            List<String> responseFieldSummary,
            List<String> serverUrls
    ) {}

    public List<ParsedOperation> parseSpecFile(
            String specFilePath,
            String content,
            Map<String, Map<String, Object>> schemaIndex) {

        Map<String, Object> root = readJson(content);
        if (root == null || !root.containsKey("paths")) {
            return List.of();
        }
        if (specFilePath != null && specFilePath.toLowerCase(Locale.ROOT).contains(".bundled.")) {
            return List.of();
        }

        String specDomain = inferSpecDomain(specFilePath);
        String openapiVersion = string(root.get("openapi"));
        List<String> serverUrls = extractServerUrls(root.get("servers"));
        Map<String, Object> paths = asMap(root.get("paths"));
        if (paths == null) return List.of();

        List<ParsedOperation> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String pathTemplate = pathEntry.getKey();
            Map<String, Object> pathItem = asMap(pathEntry.getValue());
            if (pathItem == null) continue;

            for (String method : HTTP_METHODS) {
                Map<String, Object> operation = asMap(pathItem.get(method));
                if (operation == null) continue;

                String httpMethod = method.toUpperCase(Locale.ROOT);
                String operationIdOpenapi = string(operation.get("operationId"));
                String stableId = specDomain + "|" + httpMethod + "|" + pathTemplate;
                if (!seen.add(stableId)) continue;

                String requestRef = extractSchemaRef(operation.get("requestBody"), specFilePath, schemaIndex);
                String responseRef = extractResponseSchemaRef(operation.get("responses"), specFilePath, schemaIndex);

                results.add(new ParsedOperation(
                        stableId,
                        specDomain,
                        specFilePath,
                        openapiVersion,
                        operationIdOpenapi,
                        httpMethod,
                        pathTemplate,
                        normalizePath(pathTemplate),
                        string(operation.get("summary")),
                        extractTags(operation.get("tags")),
                        requestRef,
                        responseRef,
                        fieldSummary(requestRef, schemaIndex),
                        fieldSummary(responseRef, schemaIndex),
                        serverUrls
                ));
            }
        }
        return results;
    }

    public Map<String, Map<String, Object>> buildSchemaIndex(List<SchemaFile> files) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (SchemaFile file : files) {
            Map<String, Object> parsed = readJson(file.content());
            if (parsed != null) {
                index.put(normalizeSchemaId(file.path()), parsed);
            }
        }
        return index;
    }

    public List<JsonSchemaSummaryExtractor.SchemaSummary> summarizeSchemas(
            Map<String, Map<String, Object>> schemaIndex) {
        List<JsonSchemaSummaryExtractor.SchemaSummary> results = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : schemaIndex.entrySet()) {
            List<String> nestedPaths = nestedWalker.walk(e.getKey(), e.getValue(), schemaIndex);
            results.add(schemaSummaryExtractor.summarize(e.getKey(), e.getValue(), nestedPaths));
        }
        return results;
    }

    public record SchemaFile(String path, String content) {}

    static String inferSpecDomain(String specFilePath) {
        if (specFilePath == null) return "unknown";
        String normalized = specFilePath.replace('\\', '/');
        String[] parts = normalized.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("reference".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        Path fileName = Paths.get(specFilePath).getFileName();
        if (fileName == null) return "unknown";
        String name = fileName.toString();
        int dash = name.indexOf('-');
        return dash > 0 ? name.substring(0, dash) : name.replace(".json", "");
    }

    public static String normalizePath(String path) {
        if (path == null) return null;
        return path.replaceAll("\\{[^}]+}", "{*}")
                .replaceAll("/+", "/")
                .replaceAll("/$", "");
    }

    private List<String> fieldSummary(String schemaRef, Map<String, Map<String, Object>> schemaIndex) {
        if (schemaRef == null || schemaRef.isBlank()) return List.of();
        Map<String, Object> schema = schemaIndex.get(normalizeSchemaId(schemaRef));
        if (schema == null) return List.of();
        return schemaSummaryExtractor.summarize(schemaRef, schema).topLevelFields();
    }

    @SuppressWarnings("unchecked")
    private String extractSchemaRef(Object bodyNode, String specFilePath, Map<String, Map<String, Object>> schemaIndex) {
        Map<String, Object> body = asMap(bodyNode);
        if (body == null) return null;
        Map<String, Object> content = asMap(body.get("content"));
        if (content == null) return null;
        for (String mediaType : List.of("application/json", "application/*+json")) {
            Map<String, Object> media = asMap(content.get(mediaType));
            if (media == null) continue;
            String ref = resolveRef(media.get("schema"), specFilePath, schemaIndex);
            if (ref != null) return ref;
        }
        for (Object value : content.values()) {
            Map<String, Object> media = asMap(value);
            if (media == null) continue;
            String ref = resolveRef(media.get("schema"), specFilePath, schemaIndex);
            if (ref != null) return ref;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractResponseSchemaRef(
            Object responsesNode, String specFilePath, Map<String, Map<String, Object>> schemaIndex) {
        Map<String, Object> responses = asMap(responsesNode);
        if (responses == null) return null;
        for (String code : List.of("200", "201", "202", "204", "default")) {
            Map<String, Object> response = asMap(responses.get(code));
            if (response == null) continue;
            String ref = extractSchemaRefFromContent(response.get("content"), specFilePath, schemaIndex);
            if (ref != null) return ref;
        }
        for (Object value : responses.values()) {
            Map<String, Object> response = asMap(value);
            if (response == null) continue;
            String ref = extractSchemaRefFromContent(response.get("content"), specFilePath, schemaIndex);
            if (ref != null) return ref;
        }
        return null;
    }

    private String extractSchemaRefFromContent(
            Object contentNode, String specFilePath, Map<String, Map<String, Object>> schemaIndex) {
        Map<String, Object> content = asMap(contentNode);
        if (content == null) return null;
        for (Object value : content.values()) {
            Map<String, Object> media = asMap(value);
            if (media == null) continue;
            String ref = resolveRef(media.get("schema"), specFilePath, schemaIndex);
            if (ref != null) return ref;
        }
        return null;
    }

    private String resolveRef(Object schemaNode, String specFilePath, Map<String, Map<String, Object>> schemaIndex) {
        Map<String, Object> schema = asMap(schemaNode);
        if (schema == null) return null;
        String ref = string(schema.get("$ref"));
        if (ref == null) return null;
        return resolveRefPath(ref, specFilePath, schemaIndex);
    }

    private String resolveRefPath(String ref, String specFilePath, Map<String, Map<String, Object>> schemaIndex) {
        if (ref.startsWith("#/")) {
            return ref;
        }
        String baseDir = specFilePath != null
                ? Paths.get(specFilePath).getParent().normalize().toString().replace('\\', '/')
                : "";
        String filePart = ref;
        String fragment = "";
        int hash = ref.indexOf('#');
        if (hash >= 0) {
            filePart = ref.substring(0, hash);
            fragment = ref.substring(hash);
        }
        Path resolved = Paths.get(baseDir, filePart).normalize();
        String schemaId = resolved.toString().replace('\\', '/');
        if (schemaIndex.containsKey(schemaId)) {
            return schemaId;
        }
        if (schemaIndex.containsKey(normalizeSchemaId(schemaId))) {
            return normalizeSchemaId(schemaId);
        }
        return schemaId + fragment;
    }

    static String normalizeSchemaId(String path) {
        if (path == null) return null;
        return path.replace('\\', '/');
    }

    @SuppressWarnings("unchecked")
    private List<String> extractServerUrls(Object serversNode) {
        if (!(serversNode instanceof List<?> list)) return List.of();
        List<String> urls = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> server = asMap(item);
            if (server == null) continue;
            String url = string(server.get("url"));
            if (url != null && !url.isBlank()) urls.add(url);
        }
        return List.copyOf(urls);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTags(Object tagsNode) {
        if (!(tagsNode instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private Map<String, Object> readJson(String content) {
        try {
            return mapper.readValue(content, new TypeReference<>() {});
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

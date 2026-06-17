package io.testseer.backend.ingestion.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CatalogAttributesHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogAttributesHelper() {}

    static String mergeMirrors(String existingJson, List<Map<String, Object>> newMirrors) {
        if (newMirrors == null || newMirrors.isEmpty()) return existingJson;
        try {
            Map<String, Object> root = readRoot(existingJson);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mirrors = (List<Map<String, Object>>) root.computeIfAbsent(
                    "mirrors", k -> new ArrayList<>());
            for (Map<String, Object> mirror : newMirrors) {
                if (!containsMirror(mirrors, mirror)) mirrors.add(mirror);
            }
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            return existingJson;
        }
    }

    public static String secondaryStoresForMethod(String attributesJson, String accessorFqn, String methodName) {
        if (attributesJson == null || attributesJson.isBlank()) return null;
        try {
            Map<String, Object> root = MAPPER.readValue(attributesJson, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mirrors = (List<Map<String, Object>>) root.get("mirrors");
            if (mirrors == null || mirrors.isEmpty()) return null;
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> mirror : mirrors) {
                if (matchesMethod(mirror, accessorFqn, methodName)) {
                    matched.add(stripInternalKeys(mirror));
                }
            }
            return matched.isEmpty() ? null : MAPPER.writeValueAsString(matched);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static Map<String, Object> stripInternalKeys(Map<String, Object> mirror) {
        Map<String, Object> copy = new LinkedHashMap<>(mirror);
        copy.remove("accessorFqn");
        copy.remove("methodName");
        return copy;
    }

    private static boolean matchesMethod(Map<String, Object> mirror, String accessorFqn, String methodName) {
        Object af = mirror.get("accessorFqn");
        Object mn = mirror.get("methodName");
        if (af == null && mn == null) return true;
        return accessorFqn != null && accessorFqn.equals(af) && methodName != null && methodName.equals(mn);
    }

    private static boolean containsMirror(List<Map<String, Object>> mirrors, Map<String, Object> candidate) {
        for (Map<String, Object> existing : mirrors) {
            if (String.valueOf(existing.get("physicalName")).equals(String.valueOf(candidate.get("physicalName")))
                    && String.valueOf(existing.get("methodName")).equals(String.valueOf(candidate.get("methodName")))
                    && String.valueOf(existing.get("accessorFqn")).equals(String.valueOf(candidate.get("accessorFqn")))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> readRoot(String existingJson) throws JsonProcessingException {
        if (existingJson != null && !existingJson.isBlank()) {
            return MAPPER.readValue(existingJson, new TypeReference<>() {});
        }
        return new LinkedHashMap<>();
    }
}

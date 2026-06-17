package io.testseer.backend.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

final class ConsistencyHintJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConsistencyHintJsonParser() {}

    static List<ConsistencyParticipantHintView> parseParticipants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(m -> new ConsistencyParticipantHintView(
                            string(m.get("storeType")),
                            string(m.get("physicalName")),
                            string(m.get("role")),
                            string(m.get("via")),
                            string(m.get("lagClass"))))
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    static List<ConsistencyInvariantHintView> parseInvariants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {});
            return raw.stream()
                    .map(m -> new ConsistencyInvariantHintView(
                            string(m.get("kind")),
                            string(m.get("description")),
                            string(m.get("pollHint"))))
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    static List<String> parseCorrelationKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    static ConsistencyPollStrategyView parsePollStrategy(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<String> order = raw.get("order") instanceof List<?> list
                    ? (List<String>) list
                    : List.of();
            @SuppressWarnings("unchecked")
            List<String> notes = raw.get("notes") instanceof List<?> list
                    ? (List<String>) list
                    : List.of();
            return new ConsistencyPollStrategyView(
                    order,
                    string(raw.get("primaryPollHint")),
                    notes);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String string(Object value) {
        return value != null ? value.toString() : null;
    }
}

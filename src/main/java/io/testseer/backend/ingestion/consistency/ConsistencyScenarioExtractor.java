package io.testseer.backend.ingestion.consistency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Index-time consistency scenario inference from handler touchpoints and entity mirrors.
 * Replaces {@code DualWriteScenarioExtractor} and {@code MirrorScenarioMaterializer}.
 */
@Component
public class ConsistencyScenarioExtractor {

    private static final String PATTERN_DUAL_READ = "DUAL_READ_FALLBACK";

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.ConsistencyScenarioFact> fromHandlerWrites(List<FactBatch.DataAccessFact> touchpoints) {
        Map<String, List<FactBatch.DataAccessFact>> byHandler = new LinkedHashMap<>();
        for (FactBatch.DataAccessFact fact : touchpoints) {
            if (!"WRITE".equalsIgnoreCase(fact.operation())) continue;
            String key = fact.handlerClassFqn() + "|" + fact.handlerMethod();
            byHandler.computeIfAbsent(key, k -> new ArrayList<>()).add(fact);
        }

        List<FactBatch.ConsistencyScenarioFact> scenarios = new ArrayList<>();
        for (List<FactBatch.DataAccessFact> group : byHandler.values()) {
            if (group.size() < 2) continue;
            scenarios.add(toWriteScenario(group));
        }
        return scenarios;
    }

    public List<FactBatch.ConsistencyScenarioFact> fromHandlerReads(List<FactBatch.DataAccessFact> touchpoints) {
        Map<String, List<FactBatch.DataAccessFact>> byHandler = new LinkedHashMap<>();
        for (FactBatch.DataAccessFact fact : touchpoints) {
            if (!"READ".equalsIgnoreCase(fact.operation())) continue;
            String key = fact.handlerClassFqn() + "|" + fact.handlerMethod();
            byHandler.computeIfAbsent(key, k -> new ArrayList<>()).add(fact);
        }

        List<FactBatch.ConsistencyScenarioFact> scenarios = new ArrayList<>();
        for (List<FactBatch.DataAccessFact> group : byHandler.values()) {
            Set<String> stores = new LinkedHashSet<>();
            for (FactBatch.DataAccessFact f : group) {
                if (f.storeType() != null) stores.add(f.storeType().toUpperCase(Locale.ROOT));
            }
            if (stores.size() < 2) continue;
            scenarios.add(toReadScenario(group));
        }
        return scenarios;
    }

    public List<FactBatch.ConsistencyScenarioFact> fromEntityMirrors(List<FactBatch.DataObjectFact> entities) {
        List<FactBatch.ConsistencyScenarioFact> scenarios = new ArrayList<>();
        for (FactBatch.DataObjectFact entity : entities) {
            List<Map<String, Object>> mirrors = readMirrors(entity.attributes());
            if (mirrors.isEmpty()) continue;

            String scenarioId = ConsistencyScenarioIds.fit(
                    entity.physicalName().toLowerCase(Locale.ROOT) + "-bq-mirror");
            List<Map<String, Object>> participants = new ArrayList<>();
            participants.add(participant(
                    entity.storeType(), entity.physicalName(), "PRIMARY", null, "SYNC"));
            for (Map<String, Object> mirror : mirrors) {
                participants.add(participant(
                        string(mirror.get("storeType")),
                        string(mirror.get("physicalName")),
                        "MIRROR",
                        string(mirror.get("via")),
                        "ASYNC_EVENTUAL"));
            }

            Map<String, Object> pollStrategy = Map.of(
                    "order", List.of(entity.storeType(), "BIGQUERY"),
                    "primaryPollHint", "Poll " + entity.physicalName() + " in primary store; defer BQ to async checks",
                    "notes", List.of("Do not assert BQ in timing-sensitive tests")
            );

            scenarios.add(new FactBatch.ConsistencyScenarioFact(
                    scenarioId,
                    "ASYNC_MIRROR",
                    "DATA_OBJECT",
                    entity.entityFqn(),
                    entity.storeType(),
                    entity.physicalName(),
                    "[]",
                    toJson(participants),
                    toJson(pollStrategy),
                    null,
                    "ENTITY_MIRROR_EXTRACTOR",
                    0.90,
                    null
            ));
        }
        return scenarios;
    }

    private FactBatch.ConsistencyScenarioFact toWriteScenario(List<FactBatch.DataAccessFact> group) {
        HandlerWriteScenarioClassifier.ClassifiedWriteGroup classified =
                HandlerWriteScenarioClassifier.classify(group);
        FactBatch.DataAccessFact first = group.get(0);
        String suffix = switch (classified.pattern()) {
            case HandlerWriteScenarioClassifier.PATTERN_MULTI_TABLE -> "-multi-table";
            case HandlerWriteScenarioClassifier.PATTERN_CROSS_STORE -> "-cross-store";
            default -> "-dual-write";
        };
        String scenarioId = ConsistencyScenarioIds.fit(
                slug(first.handlerClassFqn(), first.handlerMethod()) + suffix);

        List<FactBatch.DataAccessFact> sorted = new ArrayList<>(group);
        sorted.sort(java.util.Comparator
                .comparing((FactBatch.DataAccessFact f) -> f.storeType() == null ? "" : f.storeType())
                .thenComparing(f -> physicalName(f) == null ? "" : physicalName(f)));

        List<Map<String, Object>> participants = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            FactBatch.DataAccessFact f = sorted.get(i);
            HandlerWriteScenarioClassifier.Participant roleHolder = classified.participants().get(i);
            participants.add(writeParticipant(
                    f.storeType(),
                    physicalName(f),
                    roleHolder.role(),
                    f.accessorFqn(),
                    f.daoMethod(),
                    roleHolder.lagClass()));
        }

        Map<String, Object> pollStrategy = new LinkedHashMap<>();
        pollStrategy.put("order", classified.pollStoreOrder());
        pollStrategy.put("primaryPollHint", "Poll all WRITE touchpoints in handler before asserting downstream");

        return new FactBatch.ConsistencyScenarioFact(
                scenarioId,
                classified.pattern(),
                "HANDLER",
                first.handlerClassFqn() + "#" + first.handlerMethod(),
                classified.primaryStore(),
                classified.primaryPhysical(),
                correlationKeysJson(group),
                toJson(participants),
                toJson(pollStrategy),
                null,
                "HANDLER_CO_WRITE_EXTRACTOR",
                0.88,
                null
        );
    }

    private static Map<String, Object> writeParticipant(
            String storeType, String physicalName, String role,
            String via, String method, String lagClass) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("storeType", storeType);
        p.put("physicalName", physicalName);
        p.put("role", role);
        if (via != null) p.put("via", via + "." + method);
        p.put("lagClass", lagClass);
        return p;
    }

    private FactBatch.ConsistencyScenarioFact toReadScenario(List<FactBatch.DataAccessFact> group) {
        FactBatch.DataAccessFact first = group.get(0);
        String scenarioId = ConsistencyScenarioIds.fit(
                slug(first.handlerClassFqn(), first.handlerMethod()) + "-dual-read");
        List<Map<String, Object>> participants = new ArrayList<>();
        boolean primary = true;
        for (FactBatch.DataAccessFact f : group) {
            participants.add(participant(
                    f.storeType(),
                    physicalName(f),
                    primary ? "PRIMARY" : "SECONDARY",
                    null,
                    "SYNC"));
            primary = false;
        }
        Map<String, Object> pollStrategy = Map.of(
                "order", group.stream().map(FactBatch.DataAccessFact::storeType).distinct().toList(),
                "notes", List.of("Dual-read fallback — verify branch order in handler before asserting store")
        );
        return new FactBatch.ConsistencyScenarioFact(
                scenarioId,
                PATTERN_DUAL_READ,
                "HANDLER",
                first.handlerClassFqn() + "#" + first.handlerMethod(),
                first.storeType(),
                physicalName(first),
                correlationKeysJson(group),
                toJson(participants),
                toJson(pollStrategy),
                null,
                "HANDLER_DUAL_READ_EXTRACTOR",
                0.85,
                null
        );
    }

    private static Map<String, Object> participant(
            String storeType, String physicalName, String role,
            String via, String lagClass) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("storeType", storeType);
        p.put("physicalName", physicalName);
        p.put("role", role);
        if (via != null) p.put("via", via);
        p.put("lagClass", lagClass);
        return p;
    }

    private String correlationKeysJson(List<FactBatch.DataAccessFact> group) {
        for (FactBatch.DataAccessFact f : group) {
            if (f.correlationKeys() != null && !f.correlationKeys().isBlank()) {
                return f.correlationKeys();
            }
        }
        return "[]";
    }

    private static String physicalName(FactBatch.DataAccessFact f) {
        return f.tableOrEntity();
    }

    private String slug(String handlerFqn, String method) {
        String simple = handlerFqn != null && handlerFqn.contains(".")
                ? handlerFqn.substring(handlerFqn.lastIndexOf('.') + 1)
                : handlerFqn;
        return (simple + "-" + method).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private List<Map<String, Object>> readMirrors(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return List.of();
        try {
            Map<String, Object> root = mapper.readValue(attributesJson, new TypeReference<>() {});
            Object mirrors = root.get("mirrors");
            if (!(mirrors instanceof List<?> list)) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
            return out;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
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

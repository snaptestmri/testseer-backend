package io.testseer.backend.query.flowdiagram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class NodeLabelEnricher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcClient db;
    private final ObjectMapper mapper;

    public NodeLabelEnricher(JdbcClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Map<String, List<String>> loadAnnotations(String serviceId, Set<String> classFqns) {
        if (classFqns == null || classFqns.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        db.sql("""
                SELECT symbol_fqn, attributes
                FROM symbol_facts
                WHERE service_id = :svc
                  AND symbol_kind = 'CLASS'
                  AND symbol_fqn = ANY(:fqns)
                """)
                .param("svc", serviceId)
                .param("fqns", classFqns.toArray(String[]::new))
                .query((rs, row) -> {
                    String fqn = rs.getString("symbol_fqn");
                    String attrs = rs.getString("attributes");
                    List<String> annotations = parseAnnotations(attrs);
                    if (!annotations.isEmpty()) {
                        result.put(fqn, annotations);
                    }
                    return fqn;
                })
                .list();
        return result;
    }

    public Map<String, List<FlowDiagramModels.FlowDiagramGate>> loadGates(String serviceId, Set<String> classFqns) {
        if (classFqns == null || classFqns.isEmpty()) {
            return Map.of();
        }
        Map<String, List<FlowDiagramModels.FlowDiagramGate>> result = new LinkedHashMap<>();
        db.sql("""
                SELECT guarded_symbol_fqn, gate_key, effect_when_fail
                FROM flow_gate_facts
                WHERE service_id = :svc
                  AND guarded_symbol_fqn = ANY(:fqns)
                ORDER BY gate_key
                """)
                .param("svc", serviceId)
                .param("fqns", classFqns.toArray(String[]::new))
                .query((rs, row) -> {
                    String fqn = rs.getString("guarded_symbol_fqn");
                    FlowDiagramModels.FlowDiagramGate gate = new FlowDiagramModels.FlowDiagramGate(
                            rs.getString("gate_key"),
                            rs.getString("effect_when_fail"));
                    result.computeIfAbsent(fqn, k -> new ArrayList<>()).add(gate);
                    return fqn;
                })
                .list();
        return result;
    }

    private List<String> parseAnnotations(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> attrs = mapper.readValue(attributesJson, MAP_TYPE);
            Object raw = attrs.get("annotations");
            if (raw instanceof List<?> list) {
                Set<String> names = new LinkedHashSet<>();
                for (Object item : list) {
                    if (item instanceof String s) {
                        names.add(s);
                    } else if (item instanceof Map<?, ?> m && m.get("name") != null) {
                        names.add(String.valueOf(m.get("name")));
                    }
                }
                return List.copyOf(names);
            }
        } catch (Exception ignored) {
            // non-JSON attributes
        }
        return List.of();
    }
}

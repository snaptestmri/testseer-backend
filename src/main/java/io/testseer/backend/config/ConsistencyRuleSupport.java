package io.testseer.backend.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConsistencyRuleSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConsistencyRuleSupport() {}

    public static String participantsJson(MessagingRulePack.ConsistencyRule rule) {
        if (rule.participants() == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (MessagingRulePack.ConsistencyParticipantRule p : rule.participants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storeType", p.storeType());
            m.put("physicalName", p.physicalName());
            m.put("role", p.role());
            if (p.via() != null) m.put("via", p.via());
            if (p.lagClass() != null) m.put("lagClass", p.lagClass());
            list.add(m);
        }
        return toJson(list);
    }

    public static String invariantsJson(MessagingRulePack.ConsistencyRule rule) {
        if (rule.invariants() == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (MessagingRulePack.ConsistencyInvariantRule inv : rule.invariants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", inv.kind());
            m.put("description", inv.description());
            if (inv.pollHint() != null) m.put("pollHint", inv.pollHint());
            list.add(m);
        }
        return toJson(list);
    }

    public static String correlationKeysJson(MessagingRulePack.ConsistencyRule rule) {
        return toJson(rule.correlationKeys() != null ? rule.correlationKeys() : List.of());
    }

    public static String pollStrategyJson(MessagingRulePack.ConsistencyRule rule) {
        if (rule.pollStrategy() == null) return null;
        return toJson(rule.pollStrategy());
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }
}

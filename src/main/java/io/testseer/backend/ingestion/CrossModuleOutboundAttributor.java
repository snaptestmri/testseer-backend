package io.testseer.backend.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OUT-08 (BL-061): attribute outbound HTTP facts on shared-module producers to calling handler classes
 * in the workload module (e.g. {@code receipt-common} producer → {@code receiptservice} controller).
 */
@Component
public class CrossModuleOutboundAttributor {

    public List<FactBatch.OutboundCallFact> attributeToCallers(
            List<ParsedModel> models, List<FactBatch.OutboundCallFact> outboundFacts) {
        if (models == null || models.isEmpty() || outboundFacts == null || outboundFacts.isEmpty()) {
            return outboundFacts != null ? outboundFacts : List.of();
        }
        Map<String, Set<String>> callersByProducer = indexCallers(models);
        Map<String, FactBatch.OutboundCallFact> deduped = new LinkedHashMap<>();

        for (FactBatch.OutboundCallFact fact : outboundFacts) {
            deduped.putIfAbsent(dedupeKey(fact), fact);
            String producerClass = classPart(fact.sourceSymbol());
            Set<String> callers = callersByProducer.get(producerClass);
            if (callers == null || callers.isEmpty()) {
                continue;
            }
            for (String caller : callers) {
                if (caller.equals(producerClass)) {
                    continue;
                }
                FactBatch.OutboundCallFact attributed = new FactBatch.OutboundCallFact(
                        caller,
                        fact.httpMethod(),
                        fact.path(),
                        fact.evidenceSource() + "+CALLER_ATTRIBUTION",
                        Math.min(fact.confidence(), 0.88)
                );
                deduped.putIfAbsent(dedupeKey(attributed), attributed);
            }
        }
        return List.copyOf(deduped.values());
    }

    private static Map<String, Set<String>> indexCallers(List<ParsedModel> models) {
        Map<String, Set<String>> direct = new LinkedHashMap<>();
        Map<String, String> classBySimple = new LinkedHashMap<>();
        for (ParsedModel model : models) {
            if (model.classFqn() != null) {
                classBySimple.put(simpleName(model.classFqn()), model.classFqn());
            }
        }

        for (ParsedModel caller : models) {
            if (caller.classFqn() == null) {
                continue;
            }
            Set<String> callees = new LinkedHashSet<>();
            caller.methodCalls().stream()
                    .map(ParsedModel.MethodCallDef::calleeClassFqn)
                    .filter(CrossModuleOutboundAttributor::isClassFqn)
                    .forEach(callees::add);
            caller.fieldInjections().stream()
                    .map(ParsedModel.FieldInjectionDef::declaredType)
                    .map(type -> resolveTypeFqn(type, classBySimple, caller.classFqn()))
                    .filter(CrossModuleOutboundAttributor::isClassFqn)
                    .forEach(callees::add);

            for (String callee : callees) {
                direct.computeIfAbsent(callee, k -> new LinkedHashSet<>()).add(caller.classFqn());
            }
        }

        Map<String, Set<String>> transitive = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : direct.entrySet()) {
            Set<String> allCallers = new LinkedHashSet<>();
            expandTransitiveCallers(entry.getKey(), direct, allCallers, 0, 6);
            transitive.put(entry.getKey(), allCallers);
        }
        return transitive;
    }

    private static void expandTransitiveCallers(
            String callee,
            Map<String, Set<String>> direct,
            Set<String> accumulated,
            int depth,
            int maxDepth) {
        if (depth >= maxDepth) {
            return;
        }
        for (String caller : direct.getOrDefault(callee, Set.of())) {
            if (accumulated.add(caller)) {
                expandTransitiveCallers(caller, direct, accumulated, depth + 1, maxDepth);
            }
        }
    }

    private static String resolveTypeFqn(
            String type, Map<String, String> classBySimple, String owningClassFqn) {
        if (type == null) {
            return null;
        }
        if (type.contains(".")) {
            return type;
        }
        if (classBySimple.containsKey(type)) {
            return classBySimple.get(type);
        }
        int dot = owningClassFqn.lastIndexOf('.');
        return dot >= 0 ? owningClassFqn.substring(0, dot + 1) + type : type;
    }

    private static String simpleName(String fqn) {
        int dot = fqn != null ? fqn.lastIndexOf('.') : -1;
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String classPart(String sourceSymbol) {
        if (sourceSymbol == null) {
            return null;
        }
        int hash = sourceSymbol.indexOf('#');
        return hash > 0 ? sourceSymbol.substring(0, hash) : sourceSymbol;
    }

    private static String dedupeKey(FactBatch.OutboundCallFact fact) {
        return fact.sourceSymbol() + "|" + fact.httpMethod() + "|" + fact.path();
    }

    private static boolean isClassFqn(String fqn) {
        return fqn != null && fqn.contains(".") && fqn.matches("[\\w.$]+");
    }
}

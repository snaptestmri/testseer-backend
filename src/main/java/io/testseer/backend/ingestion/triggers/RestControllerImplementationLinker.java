package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * TRG-15 (BL-061): links REST entry triggers declared on API interfaces to {@code @RestController}
 * implementations (Optimus {@code *ServiceApi} + {@code *ApiController} pattern).
 */
@Component
public class RestControllerImplementationLinker {

    private static final String KIND_REST = "REST_INBOUND";
    private static final String KIND_WEBHOOK = "WEBHOOK_INBOUND";

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> link(
            List<FactBatch.EntryTriggerFact> triggers, List<ParsedModel> models) {
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        Map<String, List<ParsedModel>> implsByInterface = indexRestControllerImpls(models);

        List<FactBatch.EntryTriggerFact> linked = new ArrayList<>();
        for (FactBatch.EntryTriggerFact trigger : triggers) {
            if (!isRestTrigger(trigger) || trigger.linkedHandlerFqn() == null) {
                linked.add(trigger);
                continue;
            }
            findImplementation(implsByInterface, trigger.linkedHandlerFqn(), trigger.linkedMethod())
                    .map(impl -> withImplementation(trigger, trigger.linkedHandlerFqn(), impl))
                    .ifPresentOrElse(linked::add, () -> linked.add(trigger));
        }
        return linked;
    }

    private static boolean isRestTrigger(FactBatch.EntryTriggerFact trigger) {
        return KIND_REST.equals(trigger.triggerKind()) || KIND_WEBHOOK.equals(trigger.triggerKind());
    }

    private Map<String, List<ParsedModel>> indexRestControllerImpls(List<ParsedModel> models) {
        Map<String, List<ParsedModel>> byInterface = new LinkedHashMap<>();
        if (models == null) {
            return byInterface;
        }
        for (ParsedModel model : models) {
            if (model.classFqn() == null || !isRestController(model)) {
                continue;
            }
            for (String iface : model.implementedInterfaces()) {
                registerImpl(byInterface, iface, model);
                registerImpl(byInterface, simpleName(iface), model);
            }
        }
        return byInterface;
    }

    private static void registerImpl(
            Map<String, List<ParsedModel>> byInterface, String key, ParsedModel model) {
        if (key == null || key.isBlank()) {
            return;
        }
        byInterface.computeIfAbsent(key, k -> new ArrayList<>()).add(model);
    }

    private Optional<ParsedModel> findImplementation(
            Map<String, List<ParsedModel>> implsByInterface, String interfaceFqn, String methodName) {
        List<ParsedModel> candidates = new ArrayList<>();
        if (implsByInterface.containsKey(interfaceFqn)) {
            candidates.addAll(implsByInterface.get(interfaceFqn));
        }
        String simple = simpleName(interfaceFqn);
        if (!simple.equals(interfaceFqn) && implsByInterface.containsKey(simple)) {
            candidates.addAll(implsByInterface.get(simple));
        }
        return candidates.stream()
                .filter(impl -> methodName == null || methodName.isBlank()
                        || hasMethod(impl, methodName)
                        || implementsInterfaceForMethod(impl, interfaceFqn, methodName))
                .findFirst();
    }

    private static boolean implementsInterfaceForMethod(
            ParsedModel impl, String interfaceFqn, String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return true;
        }
        return impl.implementedInterfaces().stream().anyMatch(iface ->
                iface.equals(interfaceFqn)
                        || iface.endsWith("." + simpleName(interfaceFqn))
                        || simpleName(iface).equals(simpleName(interfaceFqn)));
    }

    private static boolean hasMethod(ParsedModel model, String methodName) {
        if (model.publicMethods().stream().anyMatch(m -> methodName.equals(m.name()))) {
            return true;
        }
        return model.methodCalls().stream().anyMatch(c -> methodName.equals(c.callerMethod()));
    }

    private static boolean isRestController(ParsedModel model) {
        return model.annotations().stream()
                .anyMatch(a -> "RestController".equals(a) || "Controller".equals(a));
    }

    private FactBatch.EntryTriggerFact withImplementation(
            FactBatch.EntryTriggerFact trigger, String interfaceFqn, ParsedModel impl) {
        return new FactBatch.EntryTriggerFact(
                trigger.triggerId(),
                trigger.triggerKind(),
                trigger.direction(),
                trigger.envLane(),
                trigger.actor(),
                trigger.boundary(),
                trigger.httpMethod(),
                trigger.pathPattern(),
                impl.classFqn(),
                trigger.linkedMethod(),
                trigger.flowStep(),
                trigger.sourceRef(),
                "REST_IMPL_LINKER",
                Math.max(trigger.confidence(), 0.94),
                mergeAttributes(trigger.attributes(), interfaceFqn, impl.classFqn())
        );
    }

    private String mergeAttributes(String existing, String interfaceFqn, String implFqn) {
        try {
            Map<String, Object> attrs = existing != null && !existing.isBlank()
                    ? mapper.readValue(existing, Map.class)
                    : new LinkedHashMap<>();
            attrs.put("handlerInterfaceFqn", interfaceFqn);
            attrs.put("handlerImplFqn", implFqn);
            attrs.put("linker", "REST_IMPL");
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return existing != null ? existing : "{}";
        }
    }

    private static String simpleName(String fqn) {
        int dot = fqn != null ? fqn.lastIndexOf('.') : -1;
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}

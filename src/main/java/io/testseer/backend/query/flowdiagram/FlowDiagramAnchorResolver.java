package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.query.EntryFlowService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class FlowDiagramAnchorResolver {

    private final EntryFlowService entryFlowService;

    public FlowDiagramAnchorResolver(EntryFlowService entryFlowService) {
        this.entryFlowService = entryFlowService;
    }

    public record ResolvedAnchor(
            FlowDiagramModels.FlowDiagramAnchor anchor,
            List<String> startNodeIds,
            EntryFlowService.EntryTriggerView trigger
    ) {}

    public ResolvedAnchor resolve(String orgId, String serviceId, String anchorParam) {
        if (anchorParam == null || anchorParam.isBlank()) {
            throw new IllegalArgumentException("anchor is required");
        }
        int colon = anchorParam.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("anchor must be triggerId:|handlerFqn:|symbolFqn:|nodeId:");
        }
        String kind = anchorParam.substring(0, colon).trim();
        String value = anchorParam.substring(colon + 1).trim();
        return switch (kind) {
            case "triggerId" -> resolveTrigger(orgId, serviceId, value);
            case "handlerFqn" -> resolveHandler(serviceId, value);
            case "symbolFqn" -> resolveSymbol(serviceId, value);
            case "nodeId" -> resolveNodeId(value);
            default -> throw new IllegalArgumentException("Unknown anchor kind: " + kind);
        };
    }

    private ResolvedAnchor resolveTrigger(String orgId, String serviceId, String triggerId) {
        EntryFlowService.EntryTriggerView trigger = findTrigger(serviceId, triggerId)
                .orElseThrow(() -> new IllegalArgumentException("trigger not found: " + triggerId));

        List<String> startIds = new ArrayList<>();
        if (trigger.linkedHandlerFqn() != null) {
            if (trigger.linkedMethod() != null && !trigger.linkedMethod().isBlank()) {
                startIds.add(GraphNodeIds.methodNode(
                        serviceId, trigger.linkedHandlerFqn(), trigger.linkedMethod()));
            }
            startIds.add(GraphNodeIds.classNode(serviceId, trigger.linkedHandlerFqn()));
        }

        String handlerDisplay = trigger.linkedHandlerFqn();
        if (handlerDisplay != null && trigger.linkedMethod() != null) {
            handlerDisplay = handlerDisplay + "." + trigger.linkedMethod();
        }

        FlowDiagramModels.FlowDiagramAnchor anchor = new FlowDiagramModels.FlowDiagramAnchor(
                "TRIGGER",
                trigger.triggerId(),
                handlerDisplay,
                trigger.linkedHandlerFqn(),
                startIds.isEmpty() ? null : startIds.get(0));

        return new ResolvedAnchor(anchor, startIds, trigger);
    }

    private ResolvedAnchor resolveHandler(String serviceId, String handlerFqn) {
        EntryFlowService.ParsedHandler parsed = EntryFlowService.parseHandlerFqn(handlerFqn);
        List<String> startIds = new ArrayList<>();
        if (parsed.method() != null) {
            startIds.add(GraphNodeIds.methodNode(serviceId, parsed.classFqn(), parsed.method()));
        }
        startIds.add(GraphNodeIds.classNode(serviceId, parsed.classFqn()));

        FlowDiagramModels.FlowDiagramAnchor anchor = new FlowDiagramModels.FlowDiagramAnchor(
                "HANDLER",
                null,
                handlerFqn,
                parsed.method() != null
                        ? parsed.classFqn() + "#" + parsed.method()
                        : parsed.classFqn(),
                startIds.get(0));

        return new ResolvedAnchor(anchor, startIds, null);
    }

    private ResolvedAnchor resolveSymbol(String serviceId, String symbolFqn) {
        List<String> startIds = new ArrayList<>();
        if (symbolFqn.contains("#")) {
            int hash = symbolFqn.indexOf('#');
            String classFqn = symbolFqn.substring(0, hash);
            String method = symbolFqn.substring(hash + 1);
            startIds.add(GraphNodeIds.methodNode(serviceId, classFqn, method));
            startIds.add(GraphNodeIds.classNode(serviceId, classFqn));
        } else {
            startIds.add(GraphNodeIds.classNode(serviceId, symbolFqn));
        }

        FlowDiagramModels.FlowDiagramAnchor anchor = new FlowDiagramModels.FlowDiagramAnchor(
                "SYMBOL", null, null, symbolFqn, startIds.get(0));
        return new ResolvedAnchor(anchor, startIds, null);
    }

    private ResolvedAnchor resolveNodeId(String nodeId) {
        FlowDiagramModels.FlowDiagramAnchor anchor = new FlowDiagramModels.FlowDiagramAnchor(
                "NODE", null, null, null, nodeId);
        return new ResolvedAnchor(anchor, List.of(nodeId), null);
    }

    private Optional<EntryFlowService.EntryTriggerView> findTrigger(String serviceId, String triggerId) {
        List<EntryFlowService.EntryTriggerView> triggers =
                entryFlowService.queryTriggers(serviceId, null, null, null, null);
        return triggers.stream()
                .filter(t -> triggerId.equals(t.triggerId()))
                .findFirst();
    }
}

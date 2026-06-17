package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.graph.RestHandlerGraphResolver;
import io.testseer.backend.query.EntryFlowService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class FlowDiagramAnchorResolver {

    private static final String REST_INBOUND = "REST_INBOUND";

    private final EntryFlowService entryFlowService;
    private final RestHandlerGraphResolver restHandlerGraphResolver;

    public FlowDiagramAnchorResolver(
            EntryFlowService entryFlowService,
            RestHandlerGraphResolver restHandlerGraphResolver) {
        this.entryFlowService = entryFlowService;
        this.restHandlerGraphResolver = restHandlerGraphResolver;
    }

    public record ResolvedAnchor(
            FlowDiagramModels.FlowDiagramAnchor anchor,
            List<String> startNodeIds,
            EntryFlowService.EntryTriggerView trigger
    ) {}

    public ResolvedAnchor resolve(String orgId, String serviceId, String anchorParam, String packagePrefix) {
        if (anchorParam == null || anchorParam.isBlank()) {
            return resolveAutoFromPackagePrefix(orgId, serviceId, packagePrefix);
        }
        return resolveExplicit(orgId, serviceId, anchorParam, false);
    }

    private ResolvedAnchor resolveAutoFromPackagePrefix(String orgId, String serviceId, String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "anchor is required when packagePrefix is omitted "
                            + "(use triggerId:|handlerFqn:|symbolFqn:|nodeId:)");
        }
        List<EntryFlowService.EntryTriggerView> triggers =
                entryFlowService.queryTriggers(
                        serviceId, null, null, null, null,
                        null, null, null, packagePrefix, false);
        Optional<EntryFlowService.EntryTriggerView> rest = triggers.stream()
                .filter(t -> REST_INBOUND.equals(t.triggerKind()))
                .filter(t -> t.pathPattern() != null && !"/".equals(t.pathPattern()))
                .findFirst();
        if (rest.isEmpty()) {
            rest = triggers.stream()
                    .filter(t -> REST_INBOUND.equals(t.triggerKind()))
                    .findFirst();
        }
        if (rest.isEmpty()) {
            throw new IllegalArgumentException(
                    "No REST_INBOUND entry trigger for packagePrefix=" + packagePrefix
                            + "; provide anchor=triggerId:... explicitly");
        }
        ResolvedAnchor resolved = resolveTrigger(orgId, serviceId, rest.get().triggerId(), true);
        return resolved;
    }

    private ResolvedAnchor resolveExplicit(String orgId, String serviceId, String anchorParam, boolean autoSelected) {
        int colon = anchorParam.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("anchor must be triggerId:|handlerFqn:|symbolFqn:|nodeId:");
        }
        String kind = anchorParam.substring(0, colon).trim();
        String value = anchorParam.substring(colon + 1).trim();
        return switch (kind) {
            case "triggerId" -> resolveTrigger(orgId, serviceId, value, autoSelected);
            case "handlerFqn" -> resolveHandler(serviceId, value, autoSelected);
            case "symbolFqn" -> resolveSymbol(serviceId, value, autoSelected);
            case "nodeId" -> resolveNodeId(value, autoSelected);
            default -> throw new IllegalArgumentException("Unknown anchor kind: " + kind);
        };
    }

    private ResolvedAnchor resolveTrigger(
            String orgId, String serviceId, String triggerId, boolean autoSelected) {
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
                startIds.isEmpty() ? null : startIds.get(0),
                autoSelected);

        return new ResolvedAnchor(anchor, startIds, trigger);
    }

    private ResolvedAnchor resolveHandler(String serviceId, String handlerFqn, boolean autoSelected) {
        EntryFlowService.ParsedHandler parsed = EntryFlowService.parseHandlerFqn(handlerFqn);
        String resolvedClassFqn = restHandlerGraphResolver.resolveImplementationClassFqn(
                serviceId, parsed.classFqn());
        List<String> startIds = new ArrayList<>();
        if (parsed.method() != null) {
            startIds.add(GraphNodeIds.methodNode(serviceId, resolvedClassFqn, parsed.method()));
        }
        startIds.add(GraphNodeIds.classNode(serviceId, resolvedClassFqn));

        FlowDiagramModels.FlowDiagramAnchor anchor = new FlowDiagramModels.FlowDiagramAnchor(
                "HANDLER",
                null,
                handlerFqn,
                parsed.method() != null
                        ? resolvedClassFqn + "#" + parsed.method()
                        : resolvedClassFqn,
                startIds.get(0),
                autoSelected);

        return new ResolvedAnchor(anchor, startIds, null);
    }

    private ResolvedAnchor resolveSymbol(String serviceId, String symbolFqn, boolean autoSelected) {
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
                "SYMBOL", null, null, symbolFqn, startIds.get(0), autoSelected);
        return new ResolvedAnchor(anchor, startIds, null);
    }

    private ResolvedAnchor resolveNodeId(String nodeId, boolean autoSelected) {
        FlowDiagramModels.FlowDiagramAnchor anchor = new FlowDiagramModels.FlowDiagramAnchor(
                "NODE", null, null, null, nodeId, autoSelected);
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

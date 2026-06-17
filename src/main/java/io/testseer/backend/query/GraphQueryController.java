package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.graph.GraphProjectionService;
import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.graph.GraphRoutingService;
import io.testseer.backend.graph.ReachabilityResult;
import io.testseer.backend.graph.RestHandlerGraphResolver;
import io.testseer.backend.query.flowdiagram.FlowDiagramModels;
import io.testseer.backend.query.flowdiagram.ServiceFlowDiagramComposer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Query — Graph", description = "Graph traversal queries: reachability, impact, neighbourhood, shared types")
@RestController
@RequestMapping("/v1/graph")
public class GraphQueryController {

    private final GraphProjectionService graphService;
    private final GraphRoutingService routingService;
    private final ServiceFlowDiagramComposer flowDiagramComposer;
    private final RestHandlerGraphResolver restHandlerGraphResolver;
    private final FreshnessResolver freshnessResolver;
    private final CacheService cache;
    private final int staleThresholdMinutes;

    public GraphQueryController(GraphProjectionService graphService,
                                GraphRoutingService routingService,
                                ServiceFlowDiagramComposer flowDiagramComposer,
                                RestHandlerGraphResolver restHandlerGraphResolver,
                                FreshnessResolver freshnessResolver,
                                CacheService cache,
                                @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.graphService = graphService;
        this.routingService = routingService;
        this.flowDiagramComposer = flowDiagramComposer;
        this.restHandlerGraphResolver = restHandlerGraphResolver;
        this.freshnessResolver = freshnessResolver;
        this.cache = cache;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Forward reachability",
               description = "Which services, classes, or methods does this node transitively reach? " +
                             "Use `type=service` for service call graph, `type=class` for class graph, " +
                             "`type=method` for method-level INVOKES/ROUTES_TO. " +
                             "Pass `symbolFqn` or `nodeId` for class/method anchors (not bare serviceId UUID).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reachability result"),
        @ApiResponse(responseCode = "202", description = "Indexing in progress"),
        @ApiResponse(responseCode = "400", description = "Missing node anchor for class/method traversal"),
        @ApiResponse(responseCode = "404", description = "Service not indexed")
    })
    @GetMapping("/reachability")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> reachability(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "Traversal type: `service` (default), `class`, or `method`")
            @RequestParam(defaultValue = "service") String type,
            @Parameter(description = "Graph node ID (class/method/service)") @RequestParam(required = false) String nodeId,
            @Parameter(description = "Class FQN — resolved to class node when nodeId omitted")
            @RequestParam(required = false) String symbolFqn,
            @Parameter(description = "Method name with symbolFqn — builds method node id")
            @RequestParam(required = false) String methodName,
            @Parameter(description = "Max depth for method traversal (default 6)") @RequestParam(defaultValue = "6") int depth,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        if (status == FreshnessStatus.INDEXING) {
            return ResponseEntity.status(202).body(ResponseEnvelope.indexing(null));
        }

        String resolvedNodeId = resolveReachabilityStartNode(
                serviceId, type, nodeId, symbolFqn, methodName, restHandlerGraphResolver);
        if (resolvedNodeId == null) {
            return ResponseEntity.badRequest().build();
        }

        final String startNodeId = resolvedNodeId;
        final String cacheKey = type + "|" + startNodeId + "|" + depth;
        @SuppressWarnings("unchecked")
        ReachabilityResult result = cache.get(orgId, repo, serviceId,
                "graph:reachability", cacheKey,
                () -> switch (type) {
                    case "class" -> graphService.classDependsOnClassForward(startNodeId);
                    case "method" -> graphService.methodForward(startNodeId, depth);
                    default -> graphService.serviceCallsServiceForward(
                            GraphNodeIds.serviceNode(serviceId));
                },
                ReachabilityResult.class);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, status, result));
    }

    @Operation(summary = "Factory processor routing table",
               description = "Runtime dispatch table from Spring factory classes (e.g. ProcessorFactory → TxnEvalProcessors).")
    @GetMapping("/routing")
    public ResponseEntity<ResponseEnvelope<GraphRoutingService.RoutingReport>> routing(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "Factory class FQN filter") @RequestParam(required = false) String factoryFqn,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        String cacheKey = factoryFqn != null ? factoryFqn : "*";
        GraphRoutingService.RoutingReport report = cache.get(orgId, repo, serviceId,
                "graph:routing", cacheKey,
                () -> routingService.queryRouting(serviceId, factoryFqn),
                GraphRoutingService.RoutingReport.class);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, status, report));
    }

    @Operation(summary = "Service flow diagram composer",
               description = "Composed ingress → orchestration → routing → messaging exits view (BL-054). "
                       + "Anchor: triggerId:|handlerFqn:|symbolFqn:|nodeId:. "
                       + "Use packagePrefix to scope consumer module. format=mermaid for Mermaid text.")
    @GetMapping("/flow-diagram")
    public ResponseEntity<ResponseEnvelope<FlowDiagramModels.FlowDiagramResult>> flowDiagram(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "Anchor: triggerId:|handlerFqn:|symbolFqn:|nodeId: "
                    + "(optional when packagePrefix is set — first REST_INBOUND trigger is used)")
            @RequestParam(required = false) String anchor,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "quotient") String orgId,
            @Parameter(description = "Java package prefix filter") @RequestParam(required = false) String packagePrefix,
            @Parameter(description = "Transitive expansion depth") @RequestParam(defaultValue = "6") int depth,
            @Parameter(description = "Include messaging egress hops") @RequestParam(defaultValue = "true") boolean includeMessaging,
            @Parameter(description = "Include external domain actors") @RequestParam(defaultValue = "true") boolean includeExternalDomain,
            @Parameter(description = "Include gate labels") @RequestParam(defaultValue = "true") boolean includeGates,
            @Parameter(description = "Response format: json or mermaid") @RequestParam(defaultValue = "json") String format,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        if (status == FreshnessStatus.INDEXING) {
            return ResponseEntity.status(202).body(ResponseEnvelope.indexing(null));
        }

        FlowDiagramModels.ComposeRequest req = new FlowDiagramModels.ComposeRequest(
                orgId, serviceId, anchor, packagePrefix, depth,
                includeMessaging, includeExternalDomain, includeGates, format);

        String cacheKey = anchor + "|" + packagePrefix + "|" + depth + "|" + includeMessaging
                + "|" + includeExternalDomain + "|" + includeGates + "|" + format;
        FlowDiagramModels.FlowDiagramResult result = cache.get(orgId, repo, serviceId,
                "graph:flow-diagram", cacheKey,
                () -> flowDiagramComposer.compose(req),
                FlowDiagramModels.FlowDiagramResult.class);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, status, result));
    }

    static String resolveReachabilityStartNode(
            String serviceId,
            String type,
            String nodeId,
            String symbolFqn,
            String methodName,
            RestHandlerGraphResolver restHandlerGraphResolver) {
        if (nodeId != null && !nodeId.isBlank()) {
            return nodeId;
        }
        return switch (type) {
            case "service" -> GraphNodeIds.serviceNode(serviceId);
            case "class" -> {
                if (symbolFqn == null || symbolFqn.isBlank()) {
                    yield null;
                }
                String resolvedFqn = restHandlerGraphResolver != null
                        ? restHandlerGraphResolver.resolveImplementationClassFqn(serviceId, symbolFqn)
                        : symbolFqn;
                yield GraphNodeIds.classNode(serviceId, resolvedFqn);
            }
            case "method" -> {
                if (symbolFqn == null || symbolFqn.isBlank() || methodName == null || methodName.isBlank()) {
                    yield null;
                }
                String resolvedFqn = restHandlerGraphResolver != null
                        ? restHandlerGraphResolver.resolveImplementationClassFqn(serviceId, symbolFqn)
                        : symbolFqn;
                yield GraphNodeIds.methodNode(serviceId, resolvedFqn, methodName);
            }
            default -> GraphNodeIds.serviceNode(serviceId);
        };
    }

    /** Test / legacy overload without interface resolution. */
    static String resolveReachabilityStartNode(
            String serviceId, String type, String nodeId, String symbolFqn, String methodName) {
        return resolveReachabilityStartNode(serviceId, type, nodeId, symbolFqn, methodName, null);
    }

    @Operation(summary = "Reverse reachability (impact analysis)",
               description = "Which services or classes would be impacted if this node changes? Traverses edges in reverse.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Impact result"),
        @ApiResponse(responseCode = "404", description = "Node not indexed")
    })
    @GetMapping("/impact")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> impact(
            @Parameter(description = "Graph node ID", required = true) @RequestParam String nodeId,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo,
            @Parameter(description = "Service identifier for freshness check", required = true) @RequestParam String serviceId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        ReachabilityResult result = cache.get(orgId, repo, serviceId,
                "graph:impact", nodeId,
                () -> graphService.reverseReachability(nodeId),
                ReachabilityResult.class);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, status, result));
    }

    @Operation(summary = "Immediate neighbourhood",
               description = "Returns all direct (depth-1) neighbours of the given node — both inbound and outbound edges.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Neighbourhood result"),
        @ApiResponse(responseCode = "404", description = "Node not indexed")
    })
    @GetMapping("/neighborhood")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> neighborhood(
            @Parameter(description = "Graph node ID") @RequestParam(required = false) String nodeId,
            @Parameter(description = "Class FQN — resolved to class node when nodeId omitted")
            @RequestParam(required = false) String symbolFqn,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo,
            @Parameter(description = "Service identifier for freshness check", required = true) @RequestParam String serviceId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        String resolvedNodeId = nodeId;
        if ((resolvedNodeId == null || resolvedNodeId.isBlank()) && symbolFqn != null && !symbolFqn.isBlank()) {
            resolvedNodeId = GraphNodeIds.classNode(serviceId, symbolFqn);
        }
        if (resolvedNodeId == null || resolvedNodeId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        final String queryNodeId = resolvedNodeId;
        ReachabilityResult result = cache.get(orgId, repo, serviceId,
                "graph:neighborhood", queryNodeId,
                () -> graphService.immediateNeighborhood(queryNodeId),
                ReachabilityResult.class);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, status, result));
    }

    @Operation(summary = "Shared type resolution",
               description = "Which services use this shared library type? Always returns CURRENT — shared types do not have their own analysis runs.")
    @ApiResponse(responseCode = "200", description = "Services using this type")
    @GetMapping("/shared-type")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> sharedType(
            @Parameter(description = "Fully-qualified type name", required = true) @RequestParam String symbolFqn) {
        ReachabilityResult result = graphService.sharedTypeResolution(symbolFqn);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, FreshnessStatus.CURRENT, result));
    }

    @Operation(summary = "Type usage fan-out",
               description = "All consumers of a given type across the entire codebase. Always returns CURRENT.")
    @ApiResponse(responseCode = "200", description = "Type consumers")
    @GetMapping("/type-fanout")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> typeFanOut(
            @Parameter(description = "Fully-qualified type name", required = true) @RequestParam String symbolFqn) {
        ReachabilityResult result = graphService.typeUsageFanOut(symbolFqn);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, FreshnessStatus.CURRENT, result));
    }

    @Operation(summary = "Cross-service boundary traversal",
               description = "Services reached when crossing service boundaries from the given node.")
    @GetMapping("/cross-service-boundary")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> crossServiceBoundary(
            @Parameter(description = "Graph node ID", required = true) @RequestParam String nodeId,
            @Parameter(description = "Service identifier for freshness check", required = true) @RequestParam String serviceId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        ReachabilityResult result = graphService.crossServiceBoundary(nodeId);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, status, result));
    }
}

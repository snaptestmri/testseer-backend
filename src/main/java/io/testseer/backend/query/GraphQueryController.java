package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.graph.GraphProjectionService;
import io.testseer.backend.graph.ReachabilityResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Query — Graph", description = "Graph traversal queries: reachability, impact, neighbourhood, shared types")
@RestController
@RequestMapping("/v1/graph")
public class GraphQueryController {

    private final GraphProjectionService graphService;
    private final FreshnessResolver freshnessResolver;
    private final CacheService cache;
    private final int staleThresholdMinutes;

    public GraphQueryController(GraphProjectionService graphService,
                                FreshnessResolver freshnessResolver,
                                CacheService cache,
                                @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.graphService = graphService;
        this.freshnessResolver = freshnessResolver;
        this.cache = cache;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Forward reachability",
               description = "Which services or classes does this node transitively call? " +
                             "Use `type=service` for service-to-service call graph, `type=class` for class dependency graph.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reachability result"),
        @ApiResponse(responseCode = "202", description = "Indexing in progress"),
        @ApiResponse(responseCode = "404", description = "Service not indexed")
    })
    @GetMapping("/reachability")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> reachability(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "Traversal type: `service` (default) or `class`") @RequestParam(defaultValue = "service") String type,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return switch (status) {
            case NOT_INDEXED -> ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
            case INDEXING    -> ResponseEntity.status(202).body(ResponseEnvelope.indexing(null));
            default -> {
                @SuppressWarnings("unchecked")
                ReachabilityResult result = cache.get(orgId, repo, serviceId,
                        "graph:reachability", type,
                        () -> "class".equals(type)
                                ? graphService.classDependsOnClassForward(serviceId)
                                : graphService.serviceCallsServiceForward(serviceId),
                        ReachabilityResult.class);
                yield ResponseEntity.ok(ResponseEnvelope.of(null, null, status, result));
            }
        };
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
            @Parameter(description = "Graph node ID", required = true) @RequestParam String nodeId,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo,
            @Parameter(description = "Service identifier for freshness check", required = true) @RequestParam String serviceId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        ReachabilityResult result = cache.get(orgId, repo, serviceId,
                "graph:neighborhood", nodeId,
                () -> graphService.immediateNeighborhood(nodeId),
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
}

package io.testseer.backend.query;

import io.testseer.backend.graph.GraphProjectionService;
import io.testseer.backend.graph.ReachabilityResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/reachability")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> reachability(
            @RequestParam String serviceId,
            @RequestParam(defaultValue = "service") String type,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo) {

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

    @GetMapping("/impact")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> impact(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo,
            @RequestParam String serviceId) {

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

    @GetMapping("/neighborhood")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> neighborhood(
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo,
            @RequestParam String serviceId) {

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

    // Shared types are library/cross-service nodes that don't have their own analysis runs.
    // Always returns CURRENT — freshness is managed at the consuming service level.
    @GetMapping("/shared-type")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> sharedType(
            @RequestParam String symbolFqn) {
        ReachabilityResult result = graphService.sharedTypeResolution(symbolFqn);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, FreshnessStatus.CURRENT, result));
    }

    // Shared types are library/cross-service nodes that don't have their own analysis runs.
    // Always returns CURRENT — freshness is managed at the consuming service level.
    @GetMapping("/type-fanout")
    public ResponseEntity<ResponseEnvelope<ReachabilityResult>> typeFanOut(
            @RequestParam String symbolFqn) {
        ReachabilityResult result = graphService.typeUsageFanOut(symbolFqn);
        return ResponseEntity.ok(ResponseEnvelope.of(null, null, FreshnessStatus.CURRENT, result));
    }
}

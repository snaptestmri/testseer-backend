package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Query — Consistency Hints", description = "Static consistency scenarios (CON Phase 1)")
@RestController
@RequestMapping("/v1/consistency")
public class ConsistencyQueryController {

    private final ConsistencyQueryService consistencyQueryService;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public ConsistencyQueryController(
            ConsistencyQueryService consistencyQueryService,
            FreshnessResolver freshnessResolver,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.consistencyQueryService = consistencyQueryService;
        this.freshnessResolver = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Consistency scenarios for a service (mirror, dual-write, rule pack)")
    @GetMapping("/scenarios")
    public ResponseEntity<ResponseEnvelope<List<ConsistencyQueryService.ConsistencyScenarioView>>> getScenarios(
            @RequestParam String serviceId,
            @RequestParam(required = false) String pattern,
            @RequestParam(required = false) String flowStep) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(
                status, consistencyQueryService.query(serviceId, pattern, flowStep));
    }
}

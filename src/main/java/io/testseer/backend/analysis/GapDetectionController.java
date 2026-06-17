package io.testseer.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.query.FreshnessHttp;
import io.testseer.backend.query.FreshnessResolver;
import io.testseer.backend.query.FreshnessStatus;
import io.testseer.backend.query.ResponseEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Query — Portfolio Gaps", description = "Test class gap detection (P12)")
@RestController
@RequestMapping("/v1/gaps")
public class GapDetectionController {

    private final GapDetectionService gapDetectionService;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public GapDetectionController(
            GapDetectionService gapDetectionService,
            FreshnessResolver freshnessResolver,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.gapDetectionService = gapDetectionService;
        this.freshnessResolver = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Portfolio test gaps — production classes without matching test class")
    @GetMapping
    public ResponseEntity<ResponseEnvelope<GapReport>> getGaps(@RequestParam String serviceId) {
        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(status, gapDetectionService.buildReport(serviceId));
    }
}

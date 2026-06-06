package io.testseer.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.query.CacheService;
import io.testseer.backend.query.FreshnessResolver;
import io.testseer.backend.query.FreshnessStatus;
import io.testseer.backend.query.ResponseEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Analysis", description = "Impact analysis and test planning based on the indexed knowledge graph")
@RestController
@RequestMapping("/v1/impact")
public class ImpactAnalysisController {

    private final ImpactAnalysisService impactService;
    private final FreshnessResolver freshnessResolver;
    private final CommitIndexValidator commitValidator;
    private final CacheService cache;
    private final int staleThresholdMinutes;

    public ImpactAnalysisController(ImpactAnalysisService impactService,
                                    FreshnessResolver freshnessResolver,
                                    CommitIndexValidator commitValidator,
                                    CacheService cache,
                                    @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.impactService         = impactService;
        this.freshnessResolver     = freshnessResolver;
        this.commitValidator       = commitValidator;
        this.cache                 = cache;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(
        summary = "PR impact analysis",
        description = """
            Given a service and commit SHA, returns the symbols changed at that commit, \
            which upstream services are affected, downstream dependencies, and suggested test scope. \
            Returns 404 if the service or commit has never been indexed.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Impact report generated",
            content = @Content(schema = @Schema(implementation = ImpactReport.class))),
        @ApiResponse(responseCode = "404", description = "Service or commit not indexed"),
        @ApiResponse(responseCode = "400", description = "Required parameter missing")
    })
    @GetMapping("/pr")
    public ResponseEntity<ResponseEnvelope<ImpactReport>> prImpact(
            @Parameter(description = "Service identifier", required = true)
            @RequestParam String serviceId,
            @Parameter(description = "Git commit SHA to analyse", required = true)
            @RequestParam String commitSha,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        if (!commitValidator.isIndexed(serviceId, commitSha)) {
            return ResponseEntity.status(404)
                    .body(ResponseEnvelope.of(null, commitSha, FreshnessStatus.NOT_INDEXED, null));
        }

        ImpactReport report = cache.get(orgId, repo, serviceId,
                "impact:pr", commitSha,
                () -> impactService.buildReport(serviceId, commitSha),
                ImpactReport.class);

        var runMeta = commitValidator.runMetaForCommit(serviceId, commitSha).orElse(null);
        var envelope = ResponseEnvelope.of(
                runMeta != null ? runMeta.indexedAt() : null,
                commitSha,
                status,
                report);

        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }
}

package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@Tag(name = "Query — Status", description = "Service indexing freshness and metadata")
@RestController
@RequestMapping("/v1/status")
public class StatusController {

    private final JdbcClient db;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public StatusController(JdbcClient db, FreshnessResolver freshnessResolver,
                            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.db = db;
        this.freshnessResolver = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Get indexing status for a service",
               description = """
                   Returns the freshness status (CURRENT, STALE, INDEXING, NOT_INDEXED) \
                   for the given service, along with the last indexed commit SHA and timestamp. \
                   This endpoint is never cached — it always reads live from analysis_runs.""")
    @ApiResponse(responseCode = "200", description = "Status returned (check freshnessStatus field)")
    @GetMapping("/{serviceId}")
    public ResponseEntity<ResponseEnvelope<Map<String, Object>>> status(
            @Parameter(description = "Unique service identifier") @PathVariable String serviceId) {

        FreshnessStatus freshness = freshnessResolver.resolve(serviceId, staleThresholdMinutes);

        Instant indexedAt = db.sql("""
                SELECT completed_at FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query(Instant.class)
                .optional()
                .orElse(null);

        String commitSha = db.sql("""
                SELECT commit_sha FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);

        var data = Map.<String, Object>of(
                "serviceId",  serviceId,
                "indexedAt",  indexedAt != null ? indexedAt.toString() : "never",
                "commitSha",  commitSha != null ? commitSha : "unknown"
        );

        return ResponseEntity.ok(ResponseEnvelope.of(indexedAt, commitSha, freshness, data));
    }
}

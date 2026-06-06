package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Tag(name = "Query — Facts", description = "Retrieve indexed facts about classes and outbound HTTP calls")
@RestController
@RequestMapping("/v1/facts")
public class FactQueryController {

    private final JdbcClient db;
    private final FreshnessResolver freshnessResolver;
    private final CacheService cache;
    private final int staleThresholdMinutes;

    public FactQueryController(JdbcClient db,
                               FreshnessResolver freshnessResolver,
                               CacheService cache,
                               @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.db = db;
        this.freshnessResolver = freshnessResolver;
        this.cache = cache;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Get symbol facts for a class",
               description = """
                   Returns all indexed symbol facts for the given class FQN within a service. \
                   Results are Redis-cached. Returns 404 if the service has never been indexed, \
                   202 if indexing is currently in progress.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Facts returned"),
        @ApiResponse(responseCode = "202", description = "Indexing in progress — stale data may be returned"),
        @ApiResponse(responseCode = "404", description = "Service not indexed")
    })
    @GetMapping("/class")
    public ResponseEntity<ResponseEnvelope<List<SymbolFactView>>> getClassFacts(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "Fully-qualified class name, e.g. com.example.OrdersController", required = true) @RequestParam String symbolFqn,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        @SuppressWarnings("unchecked")
        List<SymbolFactView> facts = cache.get(orgId, repo, serviceId,
                "facts:class", symbolFqn,
                () -> querySymbolFacts(serviceId, symbolFqn),
                (Class<List<SymbolFactView>>) (Class<?>) List.class);

        RunMeta run = latestRun(serviceId);
        var envelope = ResponseEnvelope.of(run.indexedAt(), run.commitSha(), status, facts);

        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }

    private List<SymbolFactView> querySymbolFacts(String serviceId, String symbolFqn) {
        return db.sql("""
                SELECT symbol_fqn, symbol_kind, attributes, evidence_source, confidence, indexed_at
                FROM symbol_facts
                WHERE service_id = :svcId AND symbol_fqn = :fqn
                ORDER BY indexed_at DESC
                """)
                .param("svcId", serviceId)
                .param("fqn",   symbolFqn)
                .query((rs, row) -> new SymbolFactView(
                        rs.getString("symbol_fqn"),
                        rs.getString("symbol_kind"),
                        rs.getString("attributes"),
                        rs.getString("evidence_source"),
                        rs.getDouble("confidence"),
                        rs.getTimestamp("indexed_at").toInstant()
                ))
                .list();
    }

    private record RunMeta(Instant indexedAt, String commitSha) {}

    private RunMeta latestRun(String serviceId) {
        return db.sql("""
                SELECT completed_at, commit_sha FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query((rs, row) -> new RunMeta(
                        rs.getTimestamp("completed_at") != null
                                ? rs.getTimestamp("completed_at").toInstant() : null,
                        rs.getString("commit_sha")
                ))
                .optional()
                .orElse(new RunMeta(null, null));
    }

    public record SymbolFactView(
            String symbolFqn, String symbolKind, String attributes,
            String evidenceSource, double confidence, Instant indexedAt
    ) {}

    public record OutboundCallView(
            String sourceSymbol,
            String httpMethod,
            String path,
            String evidenceSource,
            double confidence,
            Instant indexedAt
    ) {}

    @Operation(summary = "Get outbound HTTP call facts for a service",
               description = """
                   Returns all detected outbound HTTP calls made by the service. \
                   Includes RestClient/WebClient chain calls, RestTemplate explicit methods, \
                   and FeignClient interface declarations. \
                   `httpMethod` and `path` are null when only client field presence was detected \
                   (e.g. a dynamic URI in a variable). Optionally filtered to a single caller class.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Outbound call facts returned"),
        @ApiResponse(responseCode = "202", description = "Indexing in progress"),
        @ApiResponse(responseCode = "404", description = "Service not indexed")
    })
    @GetMapping("/outbound")
    public ResponseEntity<ResponseEnvelope<List<OutboundCallView>>> getOutboundFacts(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo,
            @Parameter(description = "Filter by caller class FQN (optional)") @RequestParam(required = false) String sourceSymbol) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        String cacheKey = sourceSymbol != null ? sourceSymbol : "__all__";

        @SuppressWarnings("unchecked")
        List<OutboundCallView> facts = cache.get(orgId, repo, serviceId,
                "facts:outbound", cacheKey,
                () -> queryOutboundFacts(serviceId, sourceSymbol),
                (Class<List<OutboundCallView>>) (Class<?>) List.class);

        RunMeta run = latestRun(serviceId);
        var envelope = ResponseEnvelope.of(run.indexedAt(), run.commitSha(), status, facts);
        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }

    @Operation(
        summary = "Get symbol facts for specific file paths",
        description = """
            Returns all indexed symbols (CLASS, ENDPOINT) for the given file paths \
            within a service. Used by the MCP server to map GitHub PR changed files to \
            their indexed endpoints and classes.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Symbols found (may be empty if files not indexed)"),
        @ApiResponse(responseCode = "404", description = "Service not indexed")
    })
    @GetMapping("/by-file")
    public ResponseEntity<ResponseEnvelope<List<SymbolFactView>>> getByFile(
            @Parameter(description = "Service identifier", required = true) @RequestParam String serviceId,
            @Parameter(description = "File paths to look up") @RequestParam(required = false) List<String> filePaths,
            @Parameter(description = "Organisation ID") @RequestParam(defaultValue = "acme") String orgId,
            @Parameter(description = "Repository name") @RequestParam(defaultValue = "") String repo) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        if (filePaths == null || filePaths.isEmpty()) {
            RunMeta run = latestRun(serviceId);
            return ResponseEntity.ok(ResponseEnvelope.of(run.indexedAt(), run.commitSha(), status, List.of()));
        }

        List<SymbolFactView> symbols = querySymbolsByFile(serviceId, filePaths);
        RunMeta run = latestRun(serviceId);
        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus)
                .body(ResponseEnvelope.of(run.indexedAt(), run.commitSha(), status, symbols));
    }

    private List<SymbolFactView> querySymbolsByFile(String serviceId, List<String> filePaths) {
        return db.sql("""
                SELECT DISTINCT ON (symbol_fqn, symbol_kind)
                       symbol_fqn, symbol_kind, attributes, evidence_source, confidence, indexed_at
                FROM symbol_facts
                WHERE service_id = :svcId
                  AND file_path = ANY(:paths)
                  AND symbol_kind IN ('CLASS', 'ENDPOINT')
                  AND commit_sha = (
                      SELECT commit_sha FROM analysis_runs
                      WHERE service_id = :svcId AND status = 'COMPLETE'
                      ORDER BY completed_at DESC LIMIT 1
                  )
                ORDER BY symbol_fqn, symbol_kind, indexed_at DESC
                """)
                .param("svcId", serviceId)
                .param("paths", filePaths.toArray(new String[0]))
                .query((rs, row) -> new SymbolFactView(
                        rs.getString("symbol_fqn"),
                        rs.getString("symbol_kind"),
                        rs.getString("attributes"),
                        rs.getString("evidence_source"),
                        rs.getDouble("confidence"),
                        rs.getTimestamp("indexed_at").toInstant()
                ))
                .list();
    }

    private List<OutboundCallView> queryOutboundFacts(String serviceId, String sourceSymbol) {
        String baseSql = """
                SELECT source_symbol, http_method, path, evidence_source, confidence, indexed_at
                FROM outbound_call_facts
                WHERE service_id = :svcId
                """;
        String orderSql = "ORDER BY source_symbol, http_method NULLS LAST, path NULLS LAST";

        var spec = db.sql(sourceSymbol != null
                        ? baseSql + "  AND source_symbol = :srcSym\n" + orderSql
                        : baseSql + orderSql)
                .param("svcId", serviceId);
        if (sourceSymbol != null) spec = spec.param("srcSym", sourceSymbol);

        return spec.query((rs, row) -> new OutboundCallView(
                rs.getString("source_symbol"),
                rs.getString("http_method"),
                rs.getString("path"),
                rs.getString("evidence_source"),
                rs.getDouble("confidence"),
                rs.getTimestamp("indexed_at") != null ? rs.getTimestamp("indexed_at").toInstant() : null
        )).list();
    }
}

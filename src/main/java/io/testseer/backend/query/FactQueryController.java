package io.testseer.backend.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

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

    @GetMapping("/class")
    public ResponseEntity<ResponseEnvelope<List<SymbolFactView>>> getClassFacts(
            @RequestParam String serviceId,
            @RequestParam String symbolFqn,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo) {

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

    @GetMapping("/outbound")
    public ResponseEntity<ResponseEnvelope<List<OutboundCallView>>> getOutboundFacts(
            @RequestParam String serviceId,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo,
            @RequestParam(required = false) String sourceSymbol) {

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

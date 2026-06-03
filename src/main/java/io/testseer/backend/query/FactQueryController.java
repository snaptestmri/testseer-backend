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

    @Value("${testseer.stale-threshold-minutes:60}")
    private int staleThresholdMinutes;

    public FactQueryController(JdbcClient db,
                               FreshnessResolver freshnessResolver,
                               CacheService cache) {
        this.db = db;
        this.freshnessResolver = freshnessResolver;
        this.cache = cache;
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
                "facts:class", symbolFqn.hashCode() + "",
                () -> querySymbolFacts(serviceId, symbolFqn),
                (Class<List<SymbolFactView>>) (Class<?>) List.class);

        Instant indexedAt = latestIndexedAt(serviceId);
        String commitSha  = latestCommitSha(serviceId);
        var envelope = ResponseEnvelope.of(indexedAt, commitSha, status, facts);

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

    private Instant latestIndexedAt(String serviceId) {
        return db.sql("""
                SELECT completed_at FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private String latestCommitSha(String serviceId) {
        return db.sql("""
                SELECT commit_sha FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public record SymbolFactView(
            String symbolFqn, String symbolKind, String attributes,
            String evidenceSource, double confidence, Instant indexedAt
    ) {}
}

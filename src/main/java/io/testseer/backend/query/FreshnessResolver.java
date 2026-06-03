package io.testseer.backend.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component
public class FreshnessResolver {

    private final JdbcClient db;

    public FreshnessResolver(JdbcClient db) {
        this.db = db;
    }

    public FreshnessStatus resolve(String serviceId, int staleThresholdMinutes) {
        Optional<RunSummary> latest = db.sql("""
                SELECT status, completed_at FROM analysis_runs
                WHERE service_id = :id
                ORDER BY enqueued_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query((rs, row) -> new RunSummary(
                        rs.getString("status"),
                        rs.getTimestamp("completed_at") != null
                                ? rs.getTimestamp("completed_at").toInstant() : null
                ))
                .optional();

        if (latest.isEmpty()) return FreshnessStatus.NOT_INDEXED;

        RunSummary run = latest.get();
        if ("RUNNING".equals(run.status()) || "QUEUED".equals(run.status())) {
            return FreshnessStatus.INDEXING;
        }
        if (!"COMPLETE".equals(run.status())) return FreshnessStatus.NOT_INDEXED;

        if (run.completedAt() == null) return FreshnessStatus.NOT_INDEXED;

        Instant threshold = Instant.now().minus(staleThresholdMinutes, ChronoUnit.MINUTES);
        return run.completedAt().isAfter(threshold)
                ? FreshnessStatus.CURRENT : FreshnessStatus.STALE;
    }

    private record RunSummary(String status, Instant completedAt) {}
}

package io.testseer.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TestSeerMetrics {

    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter jobsDlqTotal;
    private final AtomicLong dlqDepth = new AtomicLong(0);
    private final MeterRegistry registry;
    private final JdbcClient db;

    /** Per-org epoch-second of last successful index completion (D0 freshness panel). */
    private final ConcurrentHashMap<String, AtomicLong> lastIndexEpochByOrg =
            new ConcurrentHashMap<>();

    /** Per-gap-type counts updated by GapMetricsExporter on a schedule (D4 gap-count panel). */
    private final ConcurrentHashMap<String, AtomicLong> gapCountByType =
            new ConcurrentHashMap<>();

    public TestSeerMetrics(MeterRegistry registry, JdbcClient db) {
        this.registry = registry;
        this.db = db;

        this.cacheHit = Counter.builder("testseer.cache.hit").register(registry);
        this.cacheMiss = Counter.builder("testseer.cache.miss").register(registry);

        this.jobsDlqTotal = Counter.builder("testseer.jobs.dlq.total").register(registry);

        Gauge.builder("testseer.jobs.dlq", dlqDepth, AtomicLong::get)
                .description("Count of analysis_runs in DLQ status")
                .register(registry);
    }

    public void recordCacheHit() { cacheHit.increment(); }

    public void recordCacheMiss() { cacheMiss.increment(); }

    public void recordWebhookReceived(String event) {
        registry.counter("testseer.webhook.received", "event", event).increment();
    }

    public void recordWebhookRejected(String reason) {
        registry.counter("testseer.webhook.rejected", "reason", reason).increment();
    }

    public void recordJobEnqueued(String jobType, String orgId) {
        registry.counter("testseer.jobs.enqueued", "job_type", jobType, "org_id", safeTag(orgId))
                .increment();
    }

    public void recordJobCompleted(String jobType, String status) {
        registry.counter("testseer.jobs.completed", "job_type", jobType, "status", status)
                .increment();
    }

    public void recordJobFailed(String orgId) {
        registry.counter("testseer.analysis_runs.failed", "org_id", safeTag(orgId)).increment();
    }

    public void recordJobDlq() {
        jobsDlqTotal.increment();
        refreshDlqDepth();
    }

    public void refreshDlqDepth() {
        Integer count = db.sql("SELECT COUNT(*) FROM analysis_runs WHERE status = 'DLQ'")
                .query(Integer.class)
                .optional()
                .orElse(0);
        dlqDepth.set(count);
    }

    /**
     * Records epoch-second of a successful index completion for {@code orgId}.
     * Exposed as {@code testseer.last_index_timestamp{org_id}} for the D0 Grafana panel.
     * Called from {@link io.testseer.backend.ingestion.WorkerJobProcessor} after pipeline success.
     */
    public void recordIndexComplete(String orgId) {
        String key = safeTag(orgId);
        lastIndexEpochByOrg
                .computeIfAbsent(key, k -> {
                    AtomicLong holder = new AtomicLong(0);
                    Gauge.builder("testseer.last_index_timestamp", holder, AtomicLong::get)
                            .tag("org_id", k)
                            .description("Epoch seconds of last successful index for org")
                            .register(registry);
                    return holder;
                })
                .set(Instant.now().getEpochSecond());
    }

    /**
     * Sets the current gap count for a gap type.
     * Called by {@link io.testseer.backend.observability.GapMetricsExporter} on a schedule.
     * Exposed as {@code testseer.gaps.count{gap_type}} for the D4 Grafana panel.
     */
    public void setGapCount(String gapType, long count) {
        String key = safeTag(gapType);
        gapCountByType
                .computeIfAbsent(key, k -> {
                    AtomicLong holder = new AtomicLong(0);
                    Gauge.builder("testseer.gaps.count", holder, AtomicLong::get)
                            .tag("gap_type", k)
                            .description("Current count of data-object gaps by type")
                            .register(registry);
                    return holder;
                })
                .set(count);
    }

    public Timer.Sample startJobTimer() { return Timer.start(registry); }

    public void recordJobDuration(Timer.Sample sample, String jobType) {
        sample.stop(registry.timer("testseer.jobs.duration", "job_type", jobType));
    }

    public Timer.Sample startQueryTimer() { return Timer.start(registry); }

    public void recordQueryDuration(Timer.Sample sample, String endpoint) {
        sample.stop(registry.timer("testseer.query.duration", "endpoint", endpoint));
    }

    public void recordMcpRequest(String tool, int status) {
        registry.counter("testseer.mcp.requests", "tool", safeTag(tool), "status", String.valueOf(status))
                .increment();
    }

    private static String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}

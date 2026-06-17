package io.testseer.backend.webhook;

import java.time.Instant;
import java.util.List;

public record IngestionJob(
        String jobId,
        String jobType,      // "PR" | "PUSH" | "NIGHTLY" | "MANUAL" | "LOCAL"
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        List<String> changedFiles,
        Integer prNumber,    // null for PUSH/NIGHTLY
        Instant enqueuedAt,
        int attempt,
        Instant nextRetryAt  // null on first attempt; set by withAttempt() for retry backoff
) {
    /**
     * Returns a copy of this job with incremented attempt and a computed nextRetryAt
     * using exponential backoff (base, base×2, base×4, … capped at 10 min).
     */
    public IngestionJob withAttempt(int newAttempt, long backoffBaseMs) {
        Instant retryAt = Instant.now().plusMillis(backoffMs(newAttempt, backoffBaseMs));
        return new IngestionJob(
                jobId, jobType, orgId, repo, serviceId, commitSha,
                changedFiles, prNumber, enqueuedAt, newAttempt, retryAt
        );
    }

    /** Backoff in milliseconds: base × 2^(attempt-1), capped at 600 s. */
    static long backoffMs(int attempt, long backoffBaseMs) {
        return Math.min(backoffBaseMs * (1L << (attempt - 1)), 600_000L);
    }
}

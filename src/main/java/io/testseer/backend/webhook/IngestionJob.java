package io.testseer.backend.webhook;

import java.time.Instant;
import java.util.List;

public record IngestionJob(
        String jobId,
        String jobType,      // "PR" | "PUSH" | "NIGHTLY"
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        List<String> changedFiles,
        Integer prNumber,    // null for PUSH/NIGHTLY
        Instant enqueuedAt,
        int attempt
) {}

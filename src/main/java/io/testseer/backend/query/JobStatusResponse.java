package io.testseer.backend.query;

import java.time.Instant;

public record JobStatusResponse(
        String jobId,
        String orgId,
        String serviceId,
        String commitSha,
        String jobType,
        String status,
        int attempt,
        Instant enqueuedAt,
        Instant startedAt,
        Instant completedAt,
        String errorDetail
) {}

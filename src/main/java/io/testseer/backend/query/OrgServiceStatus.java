package io.testseer.backend.query;

public record OrgServiceStatus(
        String serviceId,
        String orgId,
        String repo,
        String serviceName,
        FreshnessStatus freshnessStatus,
        String lastCommitSha,
        String lastJobId,
        String lastJobStatus
) {}

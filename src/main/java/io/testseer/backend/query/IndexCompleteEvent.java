package io.testseer.backend.query;

import java.time.Instant;

public record IndexCompleteEvent(
        String eventType,
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        Instant indexedAt,
        String jobId,
        String scope
) {
    public static final String TYPE_COMPLETE = "INDEX_COMPLETE";
    public static final String TYPE_CLEARED = "INDEX_CLEARED";
    public static final String SCOPE_SERVICE = "SERVICE";

    public static IndexCompleteEvent complete(
            String orgId, String repo, String serviceId, String commitSha, String jobId) {
        return new IndexCompleteEvent(
                TYPE_COMPLETE, orgId, repo, serviceId, commitSha, Instant.now(), jobId, SCOPE_SERVICE);
    }

    public static IndexCompleteEvent cleared(String orgId, String repo, String serviceId) {
        return new IndexCompleteEvent(
                TYPE_CLEARED, orgId, repo, serviceId, null, Instant.now(), null, SCOPE_SERVICE);
    }
}

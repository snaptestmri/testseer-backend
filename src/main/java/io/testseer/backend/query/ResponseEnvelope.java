package io.testseer.backend.query;

import java.time.Instant;

public record ResponseEnvelope<T>(
        String schemaVersion,
        Instant indexedAt,
        String commitSha,
        FreshnessStatus freshnessStatus,
        T data
) {
    public static <T> ResponseEnvelope<T> of(
            Instant indexedAt, String commitSha,
            FreshnessStatus status, T data) {
        return new ResponseEnvelope<>("1.0", indexedAt, commitSha, status, data);
    }

    public static <T> ResponseEnvelope<T> notIndexed() {
        return new ResponseEnvelope<>("1.0", null, null, FreshnessStatus.NOT_INDEXED, null);
    }

    public static <T> ResponseEnvelope<T> indexing(T lastKnownData) {
        return new ResponseEnvelope<>("1.0", null, null, FreshnessStatus.INDEXING, lastKnownData);
    }
}

package io.testseer.backend.query;

import java.time.Instant;

public record ResponseEnvelope<T>(
        String schemaVersion,
        Instant indexedAt,
        String commitSha,
        FreshnessStatus freshnessStatus,
        T data,
        String rulePackHash,
        String liveConfigStatus,
        String liveConfigEnv,
        String livePubSubStatus,
        Integer livePubSubVerifiedCount,
        Integer livePubSubSkippedCount
) {
    public static <T> ResponseEnvelope<T> of(
            Instant indexedAt, String commitSha,
            FreshnessStatus status, T data) {
        return of(indexedAt, commitSha, status, data, null);
    }

    public static <T> ResponseEnvelope<T> of(
            Instant indexedAt, String commitSha,
            FreshnessStatus status, T data, String rulePackHash) {
        return new ResponseEnvelope<>("1.0", indexedAt, commitSha, status, data, rulePackHash,
                null, null, null, null, null);
    }

    public static <T> ResponseEnvelope<T> withLiveConfig(
            Instant indexedAt, String commitSha,
            FreshnessStatus status, T data,
            String liveConfigStatus, String liveConfigEnv) {
        return new ResponseEnvelope<>("1.0", indexedAt, commitSha, status, data, null,
                liveConfigStatus, liveConfigEnv, null, null, null);
    }

    public static <T> ResponseEnvelope<T> withLivePubSub(
            Instant indexedAt, String commitSha,
            FreshnessStatus status, T data,
            String livePubSubStatus, Integer verifiedCount, Integer skippedCount) {
        return new ResponseEnvelope<>("1.0", indexedAt, commitSha, status, data, null,
                null, null, livePubSubStatus, verifiedCount, skippedCount);
    }

    public static <T> ResponseEnvelope<T> notIndexed() {
        return new ResponseEnvelope<>("1.0", null, null, FreshnessStatus.NOT_INDEXED, null, null,
                null, null, null, null, null);
    }

    public static <T> ResponseEnvelope<T> indexing(T lastKnownData) {
        return new ResponseEnvelope<>("1.0", null, null, FreshnessStatus.INDEXING, lastKnownData, null,
                null, null, null, null, null);
    }
}

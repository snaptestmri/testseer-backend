package io.testseer.backend.query;

import org.springframework.http.ResponseEntity;

import java.time.Instant;

/** Maps {@link FreshnessStatus} to HTTP status codes for query endpoints. */
public final class FreshnessHttp {

    private FreshnessHttp() {}

    public static <T> ResponseEntity<ResponseEnvelope<T>> respond(FreshnessStatus status, T data) {
        return respond(status, null, null, data);
    }

    public static <T> ResponseEntity<ResponseEnvelope<T>> respond(
            FreshnessStatus status,
            Instant indexedAt,
            String commitSha,
            T data) {
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        var envelope = ResponseEnvelope.of(indexedAt, commitSha, status, data);
        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }

    public static <T> ResponseEntity<ResponseEnvelope<T>> respondWithLiveConfig(
            FreshnessStatus status,
            Instant indexedAt,
            String commitSha,
            T data,
            String liveConfigStatus,
            String liveConfigEnv) {
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        var envelope = ResponseEnvelope.withLiveConfig(
                indexedAt, commitSha, status, data, liveConfigStatus, liveConfigEnv);
        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }

    public static <T> ResponseEntity<ResponseEnvelope<T>> respondWithLivePubSub(
            FreshnessStatus status,
            Instant indexedAt,
            String commitSha,
            T data,
            String livePubSubStatus,
            Integer verifiedCount,
            Integer skippedCount) {
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        var envelope = ResponseEnvelope.withLivePubSub(
                indexedAt, commitSha, status, data, livePubSubStatus, verifiedCount, skippedCount);
        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }
}

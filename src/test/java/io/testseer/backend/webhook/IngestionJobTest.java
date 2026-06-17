package io.testseer.backend.webhook;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionJobTest {

    @Test
    void backoffMs_usesConfigurableBase() {
        assertThat(IngestionJob.backoffMs(1, 30_000L)).isEqualTo(30_000L);
        assertThat(IngestionJob.backoffMs(2, 30_000L)).isEqualTo(60_000L);
        assertThat(IngestionJob.backoffMs(3, 30_000L)).isEqualTo(120_000L);
        assertThat(IngestionJob.backoffMs(1, 100L)).isEqualTo(100L);
        assertThat(IngestionJob.backoffMs(2, 100L)).isEqualTo(200L);
    }

    @Test
    void backoffMs_capsAtTenMinutes() {
        assertThat(IngestionJob.backoffMs(10, 30_000L)).isEqualTo(600_000L);
    }

    @Test
    void withAttempt_setsNextRetryAtFromBase() {
        IngestionJob job = sampleJob(1);
        IngestionJob retry = job.withAttempt(2, 100L);

        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(retry.nextRetryAt()).isAfter(Instant.now().plusMillis(50));
        assertThat(retry.nextRetryAt()).isBefore(Instant.now().plusMillis(500));
    }

    private static IngestionJob sampleJob(int attempt) {
        return new IngestionJob(
                "job-1", "PR", "quotient", "repo", "svc-1", "abc123",
                List.of("src/Main.java"), 42, Instant.now(), attempt, null
        );
    }
}

package io.testseer.backend.ingestion;

import io.testseer.backend.config.ObservabilityProperties;
import io.testseer.backend.observability.TestSeerMetrics;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobFailureHandlerTest {

    @Mock KafkaJobPublisher publisher;
    @Mock AnalysisRunTracker runTracker;
    @Mock TestSeerMetrics metrics;

    JobFailureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new JobFailureHandler(
                publisher,
                runTracker,
                metrics,
                new ObservabilityProperties("X-Request-Id", "X-Job-Id", "X-TestSeer-Client", "X-MCP-Tool", false, 3, 30_000L)
        );
    }

    @Test
    void republishesWithIncrementedAttemptWhenBelowMax() {
        IngestionJob job = sampleJob(1);

        handler.handleFailure(job, KafkaTopicsConfig.TOPIC_PR, new RuntimeException("transient"));

        verify(runTracker).markFailed(job.jobId(), "transient");
        verify(runTracker).updateAttempt(job.jobId(), 2);
        ArgumentCaptor<IngestionJob> captor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(publisher).publishPrJob(captor.capture());
        assertThat(captor.getValue().attempt()).isEqualTo(2);
        verify(publisher, never()).publishDlqJob(any());
        verify(metrics).recordJobCompleted("PR", "FAILED");
        verify(metrics, never()).recordJobCompleted(anyString(), eq("DLQ"));
    }

    @Test
    void movesToDlqWhenMaxAttemptsReached() {
        IngestionJob job = sampleJob(3);

        handler.handleFailure(job, KafkaTopicsConfig.TOPIC_BATCH, new RuntimeException("persistent"));

        verify(runTracker).markDlq(job.jobId(), "persistent");
        verify(publisher).publishDlqJob(job);
        verify(publisher, never()).publishBatchJob(any());
        verify(metrics).recordJobCompleted("PR", "DLQ");
        verify(metrics).recordJobDlq();
        verify(metrics, never()).recordJobCompleted(anyString(), eq("FAILED"));
    }

    private static IngestionJob sampleJob(int attempt) {
        return new IngestionJob(
                "job-1", "PR", "quotient", "repo", "svc-1", "abc123",
                List.of("src/Main.java"), 42, Instant.now(), attempt, null
        );
    }
}

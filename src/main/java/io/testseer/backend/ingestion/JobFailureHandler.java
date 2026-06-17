package io.testseer.backend.ingestion;

import io.testseer.backend.config.ObservabilityProperties;
import io.testseer.backend.observability.TestSeerMetrics;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JobFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(JobFailureHandler.class);

    private final KafkaJobPublisher publisher;
    private final AnalysisRunTracker runTracker;
    private final TestSeerMetrics metrics;
    private final int maxAttempts;
    private final long retryBackoffBaseMs;

    public JobFailureHandler(KafkaJobPublisher publisher,
                             AnalysisRunTracker runTracker,
                             TestSeerMetrics metrics,
                             ObservabilityProperties properties) {
        this.publisher = publisher;
        this.runTracker = runTracker;
        this.metrics = metrics;
        this.maxAttempts = properties.maxJobAttempts();
        this.retryBackoffBaseMs = properties.retryBackoffBaseMs();
    }

    public void handleFailure(IngestionJob job, String sourceTopic, Exception ex) {
        metrics.recordJobFailed(job.orgId());
        runTracker.markFailed(job.jobId(), ex.getMessage());

        if (job.attempt() >= maxAttempts) {
            moveToDlq(job, ex);
            return;
        }

        metrics.recordJobCompleted(job.jobType(), "FAILED");

        IngestionJob retry = job.withAttempt(job.attempt() + 1, retryBackoffBaseMs);
        runTracker.updateAttempt(job.jobId(), retry.attempt());
        republish(sourceTopic, retry);
        log.warn("Job {} failed on attempt {} — republishing as attempt {}: {}",
                job.jobId(), job.attempt(), retry.attempt(), ex.getMessage());
    }

    private void moveToDlq(IngestionJob job, Exception ex) {
        runTracker.markDlq(job.jobId(), ex.getMessage());
        publisher.publishDlqJob(job);
        metrics.recordJobCompleted(job.jobType(), "DLQ");
        metrics.recordJobDlq();
        log.error("Job {} moved to DLQ after {} attempts: {}",
                job.jobId(), job.attempt(), ex.getMessage(), ex);
    }

    private void republish(String sourceTopic, IngestionJob retry) {
        if (KafkaTopicsConfig.TOPIC_PR.equals(sourceTopic)) {
            publisher.publishPrJob(retry);
        } else {
            publisher.publishBatchJob(retry);
        }
    }
}

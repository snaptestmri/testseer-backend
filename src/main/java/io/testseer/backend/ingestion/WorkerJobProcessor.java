package io.testseer.backend.ingestion;

import io.micrometer.core.instrument.Timer;
import io.testseer.backend.observability.MdcContext;
import io.testseer.backend.observability.TestSeerMetrics;
import io.testseer.backend.webhook.IngestionJob;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class WorkerJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(WorkerJobProcessor.class);

    private final WorkerPipeline pipeline;
    private final JobFailureHandler failureHandler;
    private final RetryScheduler retryScheduler;
    private final TestSeerMetrics metrics;

    public WorkerJobProcessor(WorkerPipeline pipeline,
                              JobFailureHandler failureHandler,
                              RetryScheduler retryScheduler,
                              TestSeerMetrics metrics) {
        this.pipeline = pipeline;
        this.failureHandler = failureHandler;
        this.retryScheduler = retryScheduler;
        this.metrics = metrics;
    }

    public void process(ConsumerRecord<String, IngestionJob> record,
                        Acknowledgment ack,
                        String sourceTopic,
                        String workerLabel) {
        IngestionJob job = record.value();
        MdcContext.runWithJob(job, () -> {
            // Honour backoff: if nextRetryAt is still in the future, re-schedule and drop.
            Instant nextRetryAt = job.nextRetryAt();
            if (nextRetryAt != null && Instant.now().isBefore(nextRetryAt)) {
                long delayMs = Duration.between(Instant.now(), nextRetryAt).toMillis();
                log.info("{} job={} not yet ready ({}ms remaining) — rescheduling",
                        workerLabel, job.jobId(), delayMs);
                retryScheduler.scheduleRepublish(job, sourceTopic, delayMs);
                ack.acknowledge();
                return;
            }

            log.info("{} processing job={} service={} attempt={}",
                    workerLabel, job.jobId(), job.serviceId(), job.attempt());
            Timer.Sample sample = metrics.startJobTimer();
            try {
                pipeline.process(job);
                metrics.recordJobCompleted(job.jobType(), "COMPLETE");
                metrics.recordJobDuration(sample, job.jobType());
                metrics.recordIndexComplete(job.orgId());
                ack.acknowledge();
            } catch (Exception ex) {
                metrics.recordJobDuration(sample, job.jobType());
                failureHandler.handleFailure(job, sourceTopic, ex);
                ack.acknowledge();
            }
        });
    }
}

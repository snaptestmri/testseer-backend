package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Schedules delayed re-publication of retry jobs.
 * Used by {@link WorkerJobProcessor} to implement exponential backoff without
 * blocking the Kafka consumer thread.
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final KafkaJobPublisher publisher;
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "testseer-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    public RetryScheduler(KafkaJobPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Re-publishes {@code job} to {@code sourceTopic} after {@code delayMs} milliseconds.
     */
    public void scheduleRepublish(IngestionJob job, String sourceTopic, long delayMs) {
        log.info("Scheduling retry for job={} in {}ms (attempt {})",
                job.jobId(), delayMs, job.attempt());
        executor.schedule(() -> republish(job, sourceTopic), delayMs, TimeUnit.MILLISECONDS);
    }

    private void republish(IngestionJob job, String sourceTopic) {
        try {
            if (KafkaTopicsConfig.TOPIC_PR.equals(sourceTopic)) {
                publisher.publishPrJob(job);
            } else {
                publisher.publishBatchJob(job);
            }
            log.info("Retry job={} republished to {}", job.jobId(), sourceTopic);
        } catch (Exception ex) {
            log.error("Failed to republish retry job={}: {}", job.jobId(), ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}

package io.testseer.backend.webhook;

import io.testseer.backend.observability.TestSeerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobPublisher.class);

    private final KafkaTemplate<String, IngestionJob> kafka;
    private final TestSeerMetrics metrics;

    public KafkaJobPublisher(KafkaTemplate<String, IngestionJob> kafka, TestSeerMetrics metrics) {
        this.kafka = kafka;
        this.metrics = metrics;
    }

    public void publishPrJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_PR, job);
    }

    public void publishBatchJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_BATCH, job);
    }

    public void publishDlqJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_DLQ, job);
    }

    private void publish(String topic, IngestionJob job) {
        metrics.recordJobEnqueued(job.jobType(), job.orgId());
        kafka.send(topic, job.serviceId(), job)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish job {} to {}: {}", job.jobId(), topic, ex.getMessage());
                    } else {
                        log.debug("Published job {} to {} offset {}",
                                job.jobId(), topic, result.getRecordMetadata().offset());
                    }
                });
    }
}

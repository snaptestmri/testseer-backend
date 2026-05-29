package io.testseer.backend.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobPublisher.class);

    private final KafkaTemplate<String, IngestionJob> kafka;

    public KafkaJobPublisher(KafkaTemplate<String, IngestionJob> kafka) {
        this.kafka = kafka;
    }

    public void publishPrJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_PR, job);
    }

    public void publishBatchJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_BATCH, job);
    }

    private void publish(String topic, IngestionJob job) {
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

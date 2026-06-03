package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class BatchWorkerConsumer {

    private static final Logger log = LoggerFactory.getLogger(BatchWorkerConsumer.class);

    private final WorkerPipeline pipeline;

    public BatchWorkerConsumer(WorkerPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(
            topics = KafkaTopicsConfig.TOPIC_BATCH,
            groupId = "testseer-workers-batch",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IngestionJob> record, Acknowledgment ack) {
        IngestionJob job = record.value();
        log.info("Batch worker processing job={} service={}", job.jobId(), job.serviceId());
        try {
            pipeline.process(job);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Batch worker failed job={}: {}", job.jobId(), ex.getMessage(), ex);
        }
    }
}

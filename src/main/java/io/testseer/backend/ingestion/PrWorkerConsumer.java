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
public class PrWorkerConsumer {

    private static final Logger log = LoggerFactory.getLogger(PrWorkerConsumer.class);

    private final WorkerPipeline pipeline;

    public PrWorkerConsumer(WorkerPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(
            topics = KafkaTopicsConfig.TOPIC_PR,
            groupId = "testseer-workers-pr",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IngestionJob> record, Acknowledgment ack) {
        IngestionJob job = record.value();
        log.info("PR worker processing job={} service={} pr={}",
                job.jobId(), job.serviceId(), job.prNumber());
        try {
            pipeline.process(job);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("PR worker failed job={}: {}", job.jobId(), ex.getMessage(), ex);
            // Do NOT acknowledge — Kafka will redeliver
        }
    }
}

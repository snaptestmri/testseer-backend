package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class BatchWorkerConsumer {

    private final WorkerJobProcessor processor;

    public BatchWorkerConsumer(WorkerJobProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            topics = KafkaTopicsConfig.TOPIC_BATCH,
            groupId = "testseer-workers-batch",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IngestionJob> record, Acknowledgment ack) {
        processor.process(record, ack, KafkaTopicsConfig.TOPIC_BATCH, "Batch worker");
    }
}

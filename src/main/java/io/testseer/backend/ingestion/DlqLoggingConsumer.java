package io.testseer.backend.ingestion;

import io.testseer.backend.observability.TestSeerMetrics;
import io.testseer.backend.observability.MdcContext;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DlqLoggingConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqLoggingConsumer.class);

    private final TestSeerMetrics metrics;

    public DlqLoggingConsumer(TestSeerMetrics metrics) {
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = KafkaTopicsConfig.TOPIC_DLQ,
            groupId = "testseer-dlq-logger",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IngestionJob> record) {
        IngestionJob job = record.value();
        MdcContext.runWithJob(job, () -> {
            log.error("DLQ message received for job={} service={} attempt={} — inspect analysis_runs",
                    job.jobId(), job.serviceId(), job.attempt());
            metrics.refreshDlqDepth();
        });
    }
}

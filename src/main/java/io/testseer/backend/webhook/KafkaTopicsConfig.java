package io.testseer.backend.webhook;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    public static final String TOPIC_PR    = "testseer.jobs.pr";
    public static final String TOPIC_BATCH = "testseer.jobs.batch";
    public static final String TOPIC_DLQ   = "testseer.jobs.dlq";

    @Bean
    public NewTopic prTopic() {
        return TopicBuilder.name(TOPIC_PR)
                .partitions(12)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic batchTopic() {
        return TopicBuilder.name(TOPIC_BATCH)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(TOPIC_DLQ)
                .partitions(3)
                .replicas(1)
                .build();
    }
}

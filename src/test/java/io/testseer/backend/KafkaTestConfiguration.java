package io.testseer.backend;

import io.testseer.backend.webhook.KafkaJobPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;

@TestConfiguration
public class KafkaTestConfiguration {

    @MockBean
    KafkaJobPublisher kafkaJobPublisher;
}

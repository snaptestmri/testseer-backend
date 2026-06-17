package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.config.MessagingRulePackLoader;
import org.springframework.core.io.FileSystemResource;

final class MessagingTestFixtures {

    private MessagingTestFixtures() {}

    static MessagingRulePackLoader quotientRulePackLoader() {
        return new MessagingRulePackLoader(
                new FileSystemResource("../config/rule-packs/quotient-messaging.yml"));
    }
}

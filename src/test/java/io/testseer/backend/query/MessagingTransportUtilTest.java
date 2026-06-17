package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingTransportUtilTest {

    @Test
    void fromAttributes_defaultsToPubsub() {
        assertThat(MessagingTransportUtil.fromAttributes(null)).isEqualTo("PUBSUB");
        assertThat(MessagingTransportUtil.fromAttributes("{}")).isEqualTo("PUBSUB");
    }

    @Test
    void fromAttributes_detectsKafka() {
        assertThat(MessagingTransportUtil.fromAttributes("{\"transport\":\"KAFKA\"}")).isEqualTo("KAFKA");
    }

    @Test
    void fromAttributes_detectsHttpPubSub() {
        assertThat(MessagingTransportUtil.fromAttributes("{\"transport\":\"HTTP_PUBSUB\"}"))
                .isEqualTo("HTTP_PUBSUB");
    }

    @Test
    void resolveHopTransport_prefersHttpPubSubWhenMixed() {
        List<MessagingFlowService.PubSubOrgView> participants = List.of(
                orgView("HTTP_PUBSUB"),
                orgView("PUBSUB"));
        assertThat(MessagingTransportUtil.resolveHopTransport(participants)).isEqualTo("HTTP_PUBSUB");
    }

    private static MessagingFlowService.PubSubOrgView orgView(String transport) {
        return new MessagingFlowService.PubSubOrgView(
                "svc", "repo", "name", "TOPIC", "DEV_T.NOTIFICATION_REQ", "dev", "PUBLISH",
                null, null, "module", "com.example.Handler", "workload", "HTTP_PUBSUB_LINKER", 0.94,
                transport, List.of(), List.of(), null);
    }
}

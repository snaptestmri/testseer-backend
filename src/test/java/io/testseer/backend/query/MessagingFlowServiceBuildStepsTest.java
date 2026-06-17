package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingFlowServiceBuildStepsTest {

    @Test
    void buildSteps_mergesMultiplePublishOutboundsPerHandler() throws Exception {
        MessagingFlowService service = new MessagingFlowService(
                null, null, null, null, null, null, null, null, null);

        List<MessagingFlowService.PubSubView> pubsub = List.of(
                pubsub("com.example.Handler", "DEV_T.NOTIFICATION_REQ", "PUBLISH",
                        "http://pubsub/publish", "HTTP_PUBSUB"),
                pubsub("com.example.Handler", "QUOT.REBATE.REWARD-STATUS.EVENTS", "PUBLISH",
                        null, "KAFKA"));

        @SuppressWarnings("unchecked")
        List<MessagingFlowService.EventFlowStep> steps = (List<MessagingFlowService.EventFlowStep>)
                invokeBuildSteps(service, pubsub);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).outbounds()).hasSize(2);
        assertThat(steps.get(0).outbounds())
                .extracting(MessagingFlowService.OutboundMsg::topicOrType)
                .containsExactlyInAnyOrder("DEV_T.NOTIFICATION_REQ", "QUOT.REBATE.REWARD-STATUS.EVENTS");
        assertThat(steps.get(0).outbounds())
                .filteredOn(o -> "DEV_T.NOTIFICATION_REQ".equals(o.topicOrType()))
                .singleElement()
                .extracting(MessagingFlowService.OutboundMsg::transport)
                .isEqualTo("HTTP_PUBSUB");
        assertThat(steps.get(0).outbound().topicOrType()).isEqualTo("DEV_T.NOTIFICATION_REQ");
    }

    private static Object invokeBuildSteps(
            MessagingFlowService service, List<MessagingFlowService.PubSubView> pubsub) throws Exception {
        Method method = MessagingFlowService.class.getDeclaredMethod(
                "buildSteps", List.class, List.class, List.class, List.class, List.class);
        method.setAccessible(true);
        return method.invoke(service, pubsub, List.of(), List.of(), List.of(), List.of());
    }

    private static MessagingFlowService.PubSubView pubsub(
            String handler, String shortId, String role, String fullResourceId, String transport) {
        return new MessagingFlowService.PubSubView(
                "TOPIC", shortId, "dev", role, "spring.key", fullResourceId,
                "module", handler, "workload", "YAML", 0.95, transport, null);
    }
}

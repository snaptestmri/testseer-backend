package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRepoFlowPresenterTest {

    @Test
    void dedupeParticipants_keepsHighestConfidencePerServiceAndRole() {
        MessagingFlowService.PubSubOrgView low = participant(
                "svc-1", "repo-a", "service-a", "PUBLISH", "com.example.Publisher", 0.5);
        MessagingFlowService.PubSubOrgView high = participant(
                "svc-1", "repo-a", "service-a", "PUBLISH", "com.example.Publisher", 0.9);
        MessagingFlowService.PubSubOrgView other = participant(
                "svc-2", "repo-b", "service-b", "SUBSCRIBE", "com.example.Consumer", 0.8);

        List<MessagingFlowService.PubSubOrgView> deduped =
                CrossRepoFlowPresenter.dedupeParticipants(List.of(low, high, other));

        assertThat(deduped).hasSize(2);
        assertThat(deduped.stream().filter(p -> "svc-1".equals(p.serviceId())).findFirst())
                .get()
                .extracting(MessagingFlowService.PubSubOrgView::confidence)
                .isEqualTo(0.9);
    }

    @Test
    void present_buildsHopSummariesNarrativeAndStructuredGaps() {
        MessagingFlowService.PubSubOrgView publisher = participant(
                "svc-pub", "orders-repo", "orders-svc", "PUBLISH", "com.example.Publisher", 1.0);
        MessagingFlowService.PubSubOrgView subscriber = participant(
                "svc-sub", "fulfillment-repo", "fulfillment-svc", "SUBSCRIBE", "com.example.Consumer", 1.0);
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.ORDER_CREATED", "KAFKA", List.of(publisher, publisher), List.of(subscriber), List.of());
        MessagingFlowService.FlowGap gap = new MessagingFlowService.FlowGap(
                "NO_SUBSCRIBER", "No subscriber indexed for topic PDN_T.DOWNSTREAM");

        CrossRepoFlowPresenter.Result result = CrossRepoFlowPresenter.present(
                "PDN_T.ORDER_CREATED", List.of(hop), List.of(gap));

        assertThat(result.hops().get(0).publishers()).hasSize(1);
        assertThat(result.hopSummaries()).hasSize(1);
        assertThat(result.hopSummaries().get(0).summaryLine())
                .contains("Hop 1")
                .contains("PDN_T.ORDER_CREATED")
                .contains("orders-svc");
        assertThat(result.gaps().get(0).hopOrder()).isNull();
        assertThat(result.gaps().get(0).topicShortId()).isEqualTo("PDN_T.DOWNSTREAM");
        assertThat(result.narrative()).isNotEmpty();
        assertThat(String.join("\n", result.narrative()))
                .contains("Cross-repo trace from PDN_T.ORDER_CREATED")
                .contains("Hop 1 · PDN_T.ORDER_CREATED")
                .contains("NO_SUBSCRIBER");
    }

    @Test
    void enrichGap_attachesHopOrderFromMatchingTopic() {
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                3, "PDN_T.ACTIVATE_OFFER.ASTRA", "PUBSUB", List.of(), List.of(), List.of());
        MessagingFlowService.FlowGap gap = new MessagingFlowService.FlowGap(
                "TERMINAL_BATCH_RETRY",
                "Topic PDN_T.ACTIVATE_OFFER.ASTRA has no Pub/Sub subscriber; continuations via BQ DLQ");

        MessagingFlowService.FlowGap enriched =
                CrossRepoFlowPresenter.enrichGap(gap, List.of(hop));

        assertThat(enriched.hopOrder()).isEqualTo(3);
        assertThat(enriched.topicShortId()).isEqualTo("PDN_T.ACTIVATE_OFFER.ASTRA");
    }

    private static MessagingFlowService.PubSubOrgView participant(
            String serviceId,
            String repo,
            String serviceName,
            String role,
            String linkedClassFqn,
            double confidence) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, repo, serviceName, "TOPIC", "PDN_T.ORDER_CREATED", "unknown", role,
                null, null, "module", linkedClassFqn, "workload", "TEST", confidence, "KAFKA",
                List.of(), List.of(), null);
    }
}

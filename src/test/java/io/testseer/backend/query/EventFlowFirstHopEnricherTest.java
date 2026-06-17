package io.testseer.backend.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventFlowFirstHopEnricherTest {

    @Mock EntryFlowService entryFlowService;

    EventFlowFirstHopEnricher enricher;

    static final String HANDLER = "com.example.RiqOfferEventConsumer";
    static final String SERVICE = "riq-partner-adapter-suite";

    @BeforeEach
    void setUp() {
        enricher = new EventFlowFirstHopEnricher(entryFlowService);
    }

    @Test
    void enrichFirstStep_attachesTriggersToFirstHandler() {
        EntryFlowService.EntryTriggerView trigger = pubsubTrigger();
        when(entryFlowService.triggersForHandler(SERVICE, HANDLER)).thenReturn(List.of(trigger));

        MessagingFlowService.EventFlowStep step = step(HANDLER);
        List<MessagingFlowService.EventFlowStep> enriched =
                enricher.enrichFirstStep(SERVICE, List.of(step));

        assertThat(enriched.get(0).inboundTriggers()).containsExactly(trigger);
    }

    @Test
    void enrichFirstStep_leavesLaterStepsUntouched() {
        when(entryFlowService.triggersForHandler(SERVICE, HANDLER)).thenReturn(List.of(pubsubTrigger()));

        MessagingFlowService.EventFlowStep first = step(HANDLER);
        MessagingFlowService.EventFlowStep second = step("com.example.OtherHandler");
        List<MessagingFlowService.EventFlowStep> enriched =
                enricher.enrichFirstStep(SERVICE, List.of(first, second));

        assertThat(enriched.get(0).inboundTriggers()).hasSize(1);
        assertThat(enriched.get(1).inboundTriggers()).isEmpty();
    }

    @Test
    void enrichFirstHopSubscribers_attachesTriggersToFirstSubscriber() {
        EntryFlowService.EntryTriggerView trigger = pubsubTrigger();
        when(entryFlowService.triggersForHandler(SERVICE, HANDLER)).thenReturn(List.of(trigger));

        MessagingFlowService.PubSubOrgView subscriber = pubSub(SERVICE, HANDLER);
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(), List.of(subscriber));

        List<MessagingFlowService.CrossRepoHop> enriched =
                enricher.enrichFirstHopSubscribers(List.of(hop));

        assertThat(enriched.get(0).subscribers().get(0).inboundTriggers()).containsExactly(trigger);
    }

    private static MessagingFlowService.EventFlowStep step(String handler) {
        return new MessagingFlowService.EventFlowStep(
                1, "workload", handler,
                List.of(), List.of(),
                List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static EntryFlowService.EntryTriggerView pubsubTrigger() {
        return new EntryFlowService.EntryTriggerView(
                "pubsub:pdn_s.riq_offer_event:com.example.riqoffereventconsumer",
                "PUBSUB_SUBSCRIBE", "INBOUND", "pdn", "pubsub", "INTERNAL",
                null, "PDN_S.RIQ_OFFER_EVENT", HANDLER, "onMessage",
                null, "riq-offer-event", "PUBSUB_LINK", 1.0, null);
    }

    private static MessagingFlowService.PubSubOrgView pubSub(String serviceId, String handler) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, "repo", "name", "SUBSCRIPTION", "PDN_S.RIQ_OFFER_EVENT", "pdn",
                "SUBSCRIBE", null, null, "module", handler, "workload", "YAML", 1.0, "PUBSUB",
                List.of(), List.of(), null);
    }
}

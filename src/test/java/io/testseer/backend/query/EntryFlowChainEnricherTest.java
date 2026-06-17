package io.testseer.backend.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntryFlowChainEnricherTest {

    @Mock JdbcClient db;
    @Mock JdbcClient.StatementSpec statementSpec;
    @Mock MessagingFlowService messagingFlowService;

    EntryFlowChainEnricher enricher;

    static final String SERVICE = "riq-partner-adapter-suite";
    static final String HANDLER = "com.example.RiqOfferEventConsumer";

    @BeforeEach
    void setUp() {
        enricher = new EntryFlowChainEnricher(db, messagingFlowService);
    }

    @Test
    void topicFromSubscriptionShortId_mapsSubscriptionToTopic() {
        assertThat(EntryFlowChainEnricher.topicFromSubscriptionShortId("PDN_S.RIQ_OFFER_EVENT"))
                .isEqualTo("PDN_T.RIQ_OFFER_EVENT");
    }

    @Test
    void resolveTopicShortId_mapsSubscribeToCanonicalTopic() {
        EntryFlowService.EntryTriggerView trigger = pubsubTrigger("PDN_S.RIQ_OFFER_EVENT");
        assertThat(enricher.resolveTopicShortId(SERVICE, trigger, "pdn"))
                .isEqualTo("PDN_T.RIQ_OFFER_EVENT");
    }

    @Test
    void resolveTraceShortId_keepsSubscriptionForLocalTrace() {
        EntryFlowService.EntryTriggerView trigger = pubsubTrigger("PDN_S.RIQ_OFFER_EVENT");
        assertThat(enricher.resolveTraceShortId(SERVICE, trigger, "pdn"))
                .isEqualTo("PDN_S.RIQ_OFFER_EVENT");
    }

    @Test
    void enrich_skipsMessagingWhenFlagsFalse() {
        EntryFlowChainEnricher.EntryFlowChain chain = enricher.enrich(
                SERVICE, "quotient", pubsubTrigger("PDN_S.RIQ_OFFER_EVENT"), "pdn",
                false, false, false, 12);

        assertThat(chain).isEqualTo(EntryFlowChainEnricher.EntryFlowChain.empty());
        verify(messagingFlowService, never()).traceTopicFlow(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void enrich_callsTraceTopicFlowWhenIncludeMessaging() {
        EntryFlowService.EntryTriggerView trigger = pubsubTrigger("PDN_S.RIQ_OFFER_EVENT");
        MessagingFlowService.EventFlowReport eventFlow = MessagingFlowService.EventFlowReport.withoutLive(
                SERVICE, "PDN_T.RIQ_OFFER_EVENT", "pdn", List.of(), List.of());
        when(messagingFlowService.traceTopicFlow(SERVICE, "PDN_S.RIQ_OFFER_EVENT", "pdn", false))
                .thenReturn(eventFlow);

        EntryFlowChainEnricher.EntryFlowChain chain = enricher.enrich(
                SERVICE, "quotient", trigger, "pdn", true, false, false, 12);

        assertThat(chain.messagingTopicShortId()).isEqualTo("PDN_T.RIQ_OFFER_EVENT");
        assertThat(chain.messagingFlow()).isSameAs(eventFlow);
        assertThat(chain.crossRepoFlow()).isNull();
    }

    private static EntryFlowService.EntryTriggerView pubsubTrigger(String subscriptionShortId) {
        return new EntryFlowService.EntryTriggerView(
                "pubsub:pdn_s.riq_offer_event:com.example.riqoffereventconsumer",
                "PUBSUB_SUBSCRIBE", "INBOUND", "pdn", "pubsub", "INTERNAL",
                null, subscriptionShortId, HANDLER, "onMessage",
                null, "riq-offer-event", "PUBSUB_LINK", 1.0, null);
    }
}

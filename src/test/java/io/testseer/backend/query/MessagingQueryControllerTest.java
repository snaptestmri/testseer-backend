package io.testseer.backend.query;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MessagingQueryController.class)
class MessagingQueryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean MessagingFlowService flowService;
    @MockBean FreshnessResolver freshnessResolver;

    @Test
    void getPubSubInventory_returns404WhenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/facts/pubsub").param("serviceId", "svc-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void getPubSubInventory_returns202WhenIndexing() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.INDEXING);
        when(flowService.queryPubSubWithLive(eq("svc-1"), isNull(), isNull(), eq(false)))
                .thenReturn(new MessagingFlowService.PubSubLiveInventoryResult<>(
                        List.of(), MessagingFlowService.LivePubSubSummary.disabled()));

        mockMvc.perform(get("/v1/facts/pubsub").param("serviceId", "svc-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.freshnessStatus").value("INDEXING"));
    }

    @Test
    void getPubSubInventory_returns200() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(flowService.queryPubSubWithLive(eq("svc-1"), isNull(), eq("pdn"), eq(false)))
                .thenReturn(new MessagingFlowService.PubSubLiveInventoryResult<>(
                        List.of(new MessagingFlowService.PubSubView(
                                "TOPIC", "PDN_T.RIQ_OFFER_EVENT", "pdn", "PUBLISH", null, null,
                                "offer-ingestion", "com.example.Publisher", "offer-ingestion-ns",
                                "YAML", 1.0, "PUBSUB", null)),
                        MessagingFlowService.LivePubSubSummary.disabled()));

        mockMvc.perform(get("/v1/facts/pubsub")
                        .param("serviceId", "svc-1")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data[0].shortId").value("PDN_T.RIQ_OFFER_EVENT"));
    }

    @Test
    void traceEventFlow_returnsGaps() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(flowService.traceTopicFlow(eq("svc-1"), eq("PDN_T.RIQ_OFFER_EVENT"), eq("pdn"), eq(false), eq(false)))
                .thenReturn(MessagingFlowService.EventFlowReport.withoutLive(
                        "svc-1", "PDN_T.RIQ_OFFER_EVENT", "pdn",
                        List.of(), List.of(new MessagingFlowService.FlowGap(
                                "MISSING_SCHEMA", "No message schema facts linked to topic flow"))));

        mockMvc.perform(get("/v1/graph/event-flow")
                        .param("serviceId", "svc-1")
                        .param("shortId", "PDN_T.RIQ_OFFER_EVENT")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gaps[0].gapType").value("MISSING_SCHEMA"));
    }

    @Test
    void getMessagingGaps_returnsReport() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(flowService.traceTopicFlow("svc-1", null, "pdn"))
                .thenReturn(MessagingFlowService.EventFlowReport.withoutLive(
                        "svc-1", null, "pdn", List.of(),
                        List.of(new MessagingFlowService.FlowGap("UNGUARDED_STEP", "No gates"))));

        mockMvc.perform(get("/v1/gaps/messaging")
                        .param("serviceId", "svc-1")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gaps[0].gapType").value("UNGUARDED_STEP"));
    }

    @Test
    void getPubSubInventory_liveVerifyDisabled_returnsEnvelopeStatus() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(flowService.queryPubSubWithLive(eq("svc-1"), isNull(), eq("pdn"), eq(true)))
                .thenReturn(new MessagingFlowService.PubSubLiveInventoryResult<>(
                        List.of(new MessagingFlowService.PubSubView(
                                "SUBSCRIPTION", "PDN_S.RIQ_OFFER_EVENT", "pdn", "SUBSCRIBE",
                                null, "projects/pdn/subscriptions/PDN_S.RIQ_OFFER_EVENT",
                                "offer-hydrator", "com.example.Consumer", "offer-hydrator-ns",
                                "YAML", 1.0, "PUBSUB", null)),
                        MessagingFlowService.LivePubSubSummary.disabled()));

        mockMvc.perform(get("/v1/facts/pubsub")
                        .param("serviceId", "svc-1")
                        .param("env", "pdn")
                        .param("liveVerify", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livePubSubStatus").value("DISABLED"));
    }

    @Test
    void getOrgPubSubInventory_returns404WhenEmpty() throws Exception {
        when(flowService.queryPubSubForOrgWithLive(eq("quotient"), eq("pdn"), eq(false)))
                .thenReturn(new MessagingFlowService.PubSubLiveInventoryResult<>(
                        List.of(), MessagingFlowService.LivePubSubSummary.disabled()));

        mockMvc.perform(get("/v1/facts/pubsub/org")
                        .param("orgId", "quotient")
                        .param("env", "pdn")
                        .param("resourceKind", "TOPIC"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void getOrgPubSubInventory_returnsTopics() throws Exception {
        when(flowService.queryPubSubForOrgWithLive(eq("quotient"), eq("pdn"), eq(false)))
                .thenReturn(new MessagingFlowService.PubSubLiveInventoryResult<>(
                        List.of(
                        new MessagingFlowService.PubSubOrgView(
                                "svc-1", "offer-ingestion", "offer-ingestion",
                                "TOPIC", "PDN_T.RIQ_OFFER_EVENT", "pdn", "PUBLISH",
                                null, null, "offer-ingestion", "com.example.Publisher",
                                "offer-ingestion-ns", "YAML", 1.0, "PUBSUB", List.of(), List.of(), null),
                        new MessagingFlowService.PubSubOrgView(
                                "svc-2", "offer-hydrator", "offer-hydrator",
                                "SUBSCRIPTION", "PDN_S.RIQ_OFFER_EVENT", "pdn", "SUBSCRIBE",
                                null, null, "offer-hydrator", "com.example.Consumer",
                                "offer-hydrator-ns", "YAML", 1.0, "PUBSUB", List.of(), List.of(), null)),
                        MessagingFlowService.LivePubSubSummary.disabled()));

        mockMvc.perform(get("/v1/facts/pubsub/org")
                        .param("orgId", "quotient")
                        .param("env", "pdn")
                        .param("resourceKind", "TOPIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].shortId").value("PDN_T.RIQ_OFFER_EVENT"));
    }

    @Test
    void traceCrossRepo_returnsLivePubSubStatusWhenDisabled() throws Exception {
        when(flowService.traceCrossRepo(eq("quotient"), eq("PDN_T.RIQ_OFFER_EVENT"), eq("pdn"), eq(12), isNull(), eq(false), eq(true), eq("runtime"), eq(false)))
                .thenReturn(new MessagingFlowService.CrossRepoFlowReport(
                        "quotient", "PDN_T.RIQ_OFFER_EVENT", "pdn",
                        List.of(), List.of(), List.of(), List.of("svc-1"), List.of(), List.of(),
                        "DISABLED", 0, 0, List.of(), List.of(), "runtime", List.of(), 0));

        mockMvc.perform(get("/v1/graph/event-flow/cross-repo")
                        .param("orgId", "quotient")
                        .param("shortId", "PDN_T.RIQ_OFFER_EVENT")
                        .param("env", "pdn")
                        .param("liveVerify", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livePubSubStatus").value("DISABLED"));
    }

    @Test
    void traceCrossRepo_returnsFollowModeOnReport() throws Exception {
        when(flowService.traceCrossRepo(eq("quotient"), eq("PDN_T.RIQ_OFFER_EVENT"), eq("pdn"), eq(12), isNull(), eq(false), eq(false), eq("inventory"), eq(false)))
                .thenReturn(new MessagingFlowService.CrossRepoFlowReport(
                        "quotient", "PDN_T.RIQ_OFFER_EVENT", "pdn",
                        List.of(), List.of(), List.of(), List.of("svc-1"), List.of(), List.of(),
                        "DISABLED", 0, 0, List.of(), List.of("BFS followMode=inventory"),
                        "inventory", List.of("followMode=causal is not implemented; fell back to runtime"), 3));

        mockMvc.perform(get("/v1/graph/event-flow/cross-repo")
                        .param("orgId", "quotient")
                        .param("shortId", "PDN_T.RIQ_OFFER_EVENT")
                        .param("env", "pdn")
                        .param("followMode", "inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followMode").value("inventory"))
                .andExpect(jsonPath("$.data.skippedExpansionCount").value(3));
    }

    @Test
    void traceCrossRepo_returns404WhenOrgEmpty() throws Exception {
        when(flowService.traceCrossRepo(eq("quotient"), eq("PDN_T.RIQ_OFFER_EVENT"), eq("pdn"), eq(12), isNull(), eq(false), eq(false), eq("runtime"), eq(false)))
                .thenReturn(new MessagingFlowService.CrossRepoFlowReport(
                        "quotient", "PDN_T.RIQ_OFFER_EVENT", "pdn",
                        List.of(), List.of(), List.of(), List.of(), List.of("missing-repo"), List.of(),
                        "DISABLED", 0, 0, List.of(), List.of(), "runtime", List.of(), 0));

        mockMvc.perform(get("/v1/graph/event-flow/cross-repo")
                        .param("orgId", "quotient")
                        .param("shortId", "PDN_T.RIQ_OFFER_EVENT")
                        .param("env", "pdn"))
                .andExpect(status().isNotFound());
    }
}

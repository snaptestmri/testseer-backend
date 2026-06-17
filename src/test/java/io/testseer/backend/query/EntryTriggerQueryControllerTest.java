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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EntryTriggerQueryController.class)
class EntryTriggerQueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean EntryFlowService entryFlowService;
    @MockBean FreshnessResolver freshnessResolver;

    @Test
    void entryTriggers_returns200() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(entryFlowService.queryTriggers(
                        eq("svc-001"), eq("pdn"), isNull(), isNull(), isNull(),
                        isNull(), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(new EntryFlowService.EntryTriggerView(
                        "freedom:post:/update", "WEBHOOK_INBOUND", "INBOUND", "pdn",
                        "freedom", "EXTERNAL", "POST", "/update",
                        "com.example.FreedomWebhookController", "handleFreedomWebhook",
                        "PAYOUT_STATUS", "FreedomWebhookController.java", "RULE_PACK", 0.92, null)));

        mockMvc.perform(get("/v1/facts/entry-triggers")
                        .param("serviceId", "svc-001")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data[0].triggerId").value("freedom:post:/update"));
    }

    @Test
    void entryTriggers_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/facts/entry-triggers")
                        .param("serviceId", "svc-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void entryFlow_returns200() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(entryFlowService.traceEntryFlow(
                        eq("svc-001"),
                        eq("freedom:post:/update"),
                        isNull(),
                        eq("pdn"),
                        eq(false),
                        eq(false),
                        eq(false),
                        isNull(),
                        eq(12),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false)))
                .thenReturn(new EntryFlowService.EntryFlowReport(
                        "svc-001", "freedom:post:/update", null, "pdn", List.of(),
                        null, null, null, List.of(), List.of(), List.of()));

        mockMvc.perform(get("/v1/graph/entry-flow")
                        .param("serviceId", "svc-001")
                        .param("triggerId", "freedom:post:/update")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.serviceId").value("svc-001"));
    }

    @Test
    void entryFlowImpact_returnsHits() throws Exception {
        when(entryFlowService.orgHasEntryTriggers("quotient")).thenReturn(true);
        when(entryFlowService.impactByHandler(
                eq("quotient"),
                eq("com.example.RiqOfferEventConsumer"),
                isNull(),
                eq("pdn")))
                .thenReturn(new EntryFlowService.EntryTriggerImpactReport(
                        "quotient",
                        "com.example.RiqOfferEventConsumer",
                        null,
                        "pdn",
                        null,
                        List.of(new EntryFlowService.EntryTriggerImpactHit(
                                "svc-sub",
                                "riq-partner-adapter-suite",
                                "riq-partner-adapter-suite",
                                "EXACT",
                                new EntryFlowService.EntryTriggerView(
                                        "pubsub:test", "PUBSUB_SUBSCRIBE", "INBOUND", "pdn",
                                        "pubsub", "INTERNAL", null, "PDN_S.RIQ_OFFER_EVENT",
                                        "com.example.RiqOfferEventConsumer", "onMessage",
                                        null, "riq-offer-event", "PUBSUB_LINK", 1.0, null)))));

        mockMvc.perform(get("/v1/graph/entry-flow/impact")
                        .param("orgId", "quotient")
                        .param("handlerFqn", "com.example.RiqOfferEventConsumer")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.triggers[0].matchKind").value("EXACT"))
                .andExpect(jsonPath("$.data.triggers[0].trigger.triggerKind").value("PUBSUB_SUBSCRIBE"));
    }

    @Test
    void entryFlowImpact_returns404WhenOrgNotIndexed() throws Exception {
        when(entryFlowService.orgHasEntryTriggers("quotient")).thenReturn(false);

        mockMvc.perform(get("/v1/graph/entry-flow/impact")
                        .param("orgId", "quotient")
                        .param("handlerFqn", "com.example.Foo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }
}

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

/**
 * Contract tests for graph API responses consumed by static/viz.html Journey tab.
 * Field names must stay stable — viz.html renderEntryFlow/renderEventFlow read them directly.
 */
class VizGraphResponseShapeTest {

    @WebMvcTest(EntryTriggerQueryController.class)
    static class EntryFlowShape {

        @Autowired MockMvc mockMvc;
        @MockBean EntryFlowService entryFlowService;
        @MockBean FreshnessResolver freshnessResolver;

        @Test
        void entryFlow_exposesFieldsVizRendererExpects() throws Exception {
            when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);

            EntryFlowService.EntryTriggerView trigger = new EntryFlowService.EntryTriggerView(
                    "post:/orders/create", "REST_INBOUND", "INBOUND", "pdn",
                    "freedom", "EXTERNAL", "POST", "/orders/create",
                    "com.example.orders.OrderController", "createOrder",
                    "CREATE_ORDER", "OrderController.java", "RULE_PACK", 0.92, null);

            EntryFlowService.DataAccessSummary read = new EntryFlowService.DataAccessSummary(
                    "com.example.orders.OrderController", "createOrder", "READ", "POSTGRES", "orders");
            EntryFlowService.DataAccessSummary write = new EntryFlowService.DataAccessSummary(
                    "com.example.orders.OrderController", "createOrder", "WRITE", "POSTGRES", "payments");
            EntryFlowService.GateSummary gate = new EntryFlowService.GateSummary(
                    "com.example.orders.OrderController", "FEATURE_FLAG", "WEBHOOK_ENABLED",
                    "true", "SKIP", "flag must be on");

            when(entryFlowService.traceEntryFlow(
                            eq("svc-orders"),
                            isNull(),
                            eq("/orders/create"),
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
                            "svc-orders", null, "/orders/create", "pdn",
                            List.of(new EntryFlowService.EntryFlowStep(
                                    1, trigger, List.of(read), List.of(write), List.of(gate))),
                            null, null, null, List.of(), List.of(), List.of()));

            mockMvc.perform(get("/v1/graph/entry-flow")
                            .param("serviceId", "svc-orders")
                            .param("path", "/orders/create")
                            .param("env", "pdn"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                    .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                    .andExpect(jsonPath("$.data.serviceId").value("svc-orders"))
                    .andExpect(jsonPath("$.data.envLane").value("pdn"))
                    .andExpect(jsonPath("$.data.steps[0].order").value(1))
                    .andExpect(jsonPath("$.data.steps[0].trigger.triggerKind").value("REST_INBOUND"))
                    .andExpect(jsonPath("$.data.steps[0].trigger.httpMethod").value("POST"))
                    .andExpect(jsonPath("$.data.steps[0].trigger.pathPattern").value("/orders/create"))
                    .andExpect(jsonPath("$.data.steps[0].trigger.linkedHandlerFqn")
                            .value("com.example.orders.OrderController"))
                    .andExpect(jsonPath("$.data.steps[0].trigger.linkedMethod").value("createOrder"))
                    .andExpect(jsonPath("$.data.steps[0].trigger.actor").value("freedom"))
                    .andExpect(jsonPath("$.data.steps[0].trigger.boundary").value("EXTERNAL"))
                    .andExpect(jsonPath("$.data.steps[0].reads[0].tableOrEntity").value("orders"))
                    .andExpect(jsonPath("$.data.steps[0].reads[0].storeType").value("POSTGRES"))
                    .andExpect(jsonPath("$.data.steps[0].writes[0].tableOrEntity").value("payments"))
                    .andExpect(jsonPath("$.data.steps[0].gates[0].gateKey").value("WEBHOOK_ENABLED"))
                    .andExpect(jsonPath("$.data.steps[0].gates[0].requiredValue").value("true"));
        }
    }

    @WebMvcTest(MessagingQueryController.class)
    static class CrossRepoFlowShape {

        @Autowired MockMvc mockMvc;
        @MockBean MessagingFlowService flowService;
        @MockBean FreshnessResolver freshnessResolver;

        @Test
        void crossRepoFlow_exposesFieldsVizRendererExpects() throws Exception {
            ConsistencyHintView hint = new ConsistencyHintView(
                    "sc-dual-write", "DUAL_WRITE", "POSTGRES", "orders",
                    List.of(), List.of(), null, List.of(), "RULE_PACK", 0.9, List.of());

            MessagingFlowService.PubSubOrgView publisher = new MessagingFlowService.PubSubOrgView(
                    "svc-orders", "orders-repo", "orders-svc", "TOPIC",
                    "PDN_T.ORDER_CREATED", "pdn", "PUBLISH",
                    null, null, "orders-module", "com.example.Publisher",
                    "orders-ns", "YAML", 1.0, "PUBSUB", List.of(), List.of(), null);

            MessagingFlowService.PubSubOrgView subscriber = new MessagingFlowService.PubSubOrgView(
                    "svc-fulfillment", "fulfillment-repo", "fulfillment-svc", "SUBSCRIPTION",
                    "PDN_T.ORDER_CREATED", "pdn", "SUBSCRIBE",
                    null, null, "fulfillment-module", "com.example.Subscriber",
                    "fulfillment-ns", "YAML", 1.0, "PUBSUB", List.of(hint), List.of(), null);

            MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                    1, "PDN_T.ORDER_CREATED", "PUBSUB", List.of(publisher), List.of(subscriber), List.of());

            MessagingFlowService.ExternalEndpointView external = new MessagingFlowService.ExternalEndpointView(
                    "ep-1", "partner-x", "createOrder", "POST",
                    "https://api.partner.com/orders", null, "pdn", "EXTERNAL",
                    null, "com.example.Client", "com.example.HttpClient",
                    "FULFILL", "OAUTH", "YAML", 1.0, "svc-orders", "orders-svc");

            when(flowService.traceCrossRepo(
                    eq("quotient"), eq("PDN_T.ORDER_CREATED"), eq("pdn"), eq(12), isNull(), eq(false), eq(false), eq("runtime"), eq(false)))
                    .thenReturn(new MessagingFlowService.CrossRepoFlowReport(
                            "quotient", "PDN_T.ORDER_CREATED", "pdn",
                            List.of(hop),
                            List.of(new MessagingFlowService.FlowGap(
                                    "MISSING_SUBSCRIBER", "No downstream subscriber")),
                            List.of(hint),
                            List.of("svc-orders", "svc-fulfillment"),
                            List.of("legacy-repo"),
                            List.of(external),
                            "DISABLED", 0, 0,
                            List.of(), List.of(), "runtime", List.of(), 0));

            mockMvc.perform(get("/v1/graph/event-flow/cross-repo")
                            .param("orgId", "quotient")
                            .param("shortId", "PDN_T.ORDER_CREATED")
                            .param("env", "pdn"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                    .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                    .andExpect(jsonPath("$.data.orgId").value("quotient"))
                    .andExpect(jsonPath("$.data.startTopic").value("PDN_T.ORDER_CREATED"))
                    .andExpect(jsonPath("$.data.envLane").value("pdn"))
                    .andExpect(jsonPath("$.data.hops[0].order").value(1))
                    .andExpect(jsonPath("$.data.hops[0].topicShortId").value("PDN_T.ORDER_CREATED"))
                    .andExpect(jsonPath("$.data.hops[0].transport").value("PUBSUB"))
                    .andExpect(jsonPath("$.data.hops[0].publishers[0].serviceId").value("svc-orders"))
                    .andExpect(jsonPath("$.data.hops[0].publishers[0].serviceName").value("orders-svc"))
                    .andExpect(jsonPath("$.data.hops[0].publishers[0].repo").value("orders-repo"))
                    .andExpect(jsonPath("$.data.hops[0].publishers[0].role").value("PUBLISH"))
                    .andExpect(jsonPath("$.data.hops[0].publishers[0].workloadName").value("orders-ns"))
                    .andExpect(jsonPath("$.data.hops[0].publishers[0].linkedClassFqn")
                            .value("com.example.Publisher"))
                    .andExpect(jsonPath("$.data.hops[0].subscribers[0].serviceId").value("svc-fulfillment"))
                    .andExpect(jsonPath("$.data.hops[0].subscribers[0].serviceName").value("fulfillment-svc"))
                    .andExpect(jsonPath("$.data.gaps[0].gapType").value("MISSING_SUBSCRIBER"))
                    .andExpect(jsonPath("$.data.gaps[0].description").value("No downstream subscriber"))
                    .andExpect(jsonPath("$.data.consistencyHints[0].pattern").value("DUAL_WRITE"))
                    .andExpect(jsonPath("$.data.missingBundleRepos[0]").value("legacy-repo"))
                    .andExpect(jsonPath("$.data.externalEndpoints[0].httpMethod").value("POST"))
                    .andExpect(jsonPath("$.data.externalEndpoints[0].urlTemplate")
                            .value("https://api.partner.com/orders"))
                    .andExpect(jsonPath("$.data.externalEndpoints[0].partnerSlug").value("partner-x"));
        }
    }
}

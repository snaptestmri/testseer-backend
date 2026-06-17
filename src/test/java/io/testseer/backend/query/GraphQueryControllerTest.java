package io.testseer.backend.query;

import io.testseer.backend.graph.GraphProjectionService;
import io.testseer.backend.graph.GraphRoutingService;
import io.testseer.backend.graph.ReachabilityResult;
import io.testseer.backend.graph.RestHandlerGraphResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({FactQueryController.class, GraphQueryController.class})
class GraphQueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JdbcClient jdbcClient;
    @MockBean GraphProjectionService graphService;
    @MockBean GraphRoutingService graphRoutingService;
    @MockBean io.testseer.backend.query.flowdiagram.ServiceFlowDiagramComposer flowDiagramComposer;
    @MockBean RestHandlerGraphResolver restHandlerGraphResolver;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean CacheService cacheService;

    @Test
    void graphReachability_returns200_withEnvelope() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(cacheService.get(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> supplier = inv.getArgument(5);
                    return supplier.get();
                });
        when(graphService.serviceCallsServiceForward("svc-001"))
                .thenReturn(new ReachabilityResult(List.of("svc-002"), List.of()));

        mockMvc.perform(get("/v1/graph/reachability")
                        .param("serviceId", "svc-001")
                        .param("type", "service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.nodeIds[0]").value("svc-002"));
    }

    @Test
    void graphReachability_returns202_whenIndexing() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.INDEXING);

        mockMvc.perform(get("/v1/graph/reachability")
                        .param("serviceId", "svc-001")
                        .param("type", "service"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.freshnessStatus").value("INDEXING"));
    }

    @Test
    void graphReachability_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/graph/reachability")
                        .param("serviceId", "svc-missing")
                        .param("type", "service"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }
}

package io.testseer.backend.query;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FactQueryController.class, GraphQueryController.class})
class ExternalEndpointFactControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JdbcClient jdbcClient;
    @MockBean io.testseer.backend.graph.GraphProjectionService graphService;
    @MockBean io.testseer.backend.graph.GraphRoutingService graphRoutingService;
    @MockBean io.testseer.backend.query.flowdiagram.ServiceFlowDiagramComposer flowDiagramComposer;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean CacheService cacheService;

    @Test
    void externalEndpoints_returns200() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(cacheService.get(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> supplier = inv.getArgument(5);
                    return supplier.get();
                });
        when(jdbcClient.sql(anyString())).thenReturn(
                org.mockito.Mockito.mock(
                        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class,
                        org.mockito.Answers.RETURNS_DEEP_STUBS
                )
        );

        mockMvc.perform(get("/v1/facts/external-endpoints")
                        .param("serviceId", "svc-001")
                        .param("env", "pdn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));
    }

    @Test
    void externalEndpoints_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/facts/external-endpoints")
                        .param("serviceId", "svc-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }
}

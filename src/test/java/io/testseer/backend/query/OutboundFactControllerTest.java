package io.testseer.backend.query;

import io.testseer.backend.graph.GraphProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.Answers;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({FactQueryController.class, GraphQueryController.class})
class OutboundFactControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS) JdbcClient jdbcClient;
    @MockBean GraphProjectionService graphService;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean CacheService cacheService;

    @Test
    void outbound_returns200_whenCurrent() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(cacheService.get(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/v1/facts/outbound")
                        .param("serviceId", "svc-001")
                        .param("orgId", "acme")
                        .param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));
    }

    @Test
    void outbound_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/facts/outbound")
                        .param("serviceId", "svc-missing")
                        .param("orgId", "acme")
                        .param("repo", "repo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void outbound_returns202_whenIndexing() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.INDEXING);

        mockMvc.perform(get("/v1/facts/outbound")
                        .param("serviceId", "svc-001")
                        .param("orgId", "acme")
                        .param("repo", "repo"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.freshnessStatus").value("INDEXING"));
    }
}

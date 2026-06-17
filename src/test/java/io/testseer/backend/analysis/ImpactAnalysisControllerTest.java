package io.testseer.backend.analysis;

import io.testseer.backend.query.CacheService;
import io.testseer.backend.query.FreshnessResolver;
import io.testseer.backend.query.FreshnessStatus;
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

@WebMvcTest(ImpactAnalysisController.class)
class ImpactAnalysisControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ImpactAnalysisService impactService;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean CommitIndexValidator commitValidator;
    @MockBean CacheService cacheService;

    @Test
    void impact_returns200_withReport() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(commitValidator.isIndexed("svc-001", "abc123")).thenReturn(true);
        when(commitValidator.runMetaForCommit("svc-001", "abc123"))
                .thenReturn(java.util.Optional.of(new CommitIndexValidator.RunMeta(null, "abc123")));
        when(cacheService.get(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(ImpactReport.class))).thenReturn(
                ImpactReport.withoutArtifactImpact("svc-001", "abc123",
                        List.of(new ImpactReport.ChangedSymbol(
                                "io.orders.OrderController", "CLASS",
                                "src/main/java/io/orders/OrderController.java",
                                null, null)),
                        List.of(), List.of(), List.of(),
                        List.of(new ImpactReport.SuggestedTest(
                                "UNIT", "io.orders.OrderControllerTest",
                                null, true, "Tests changed class OrderController")),
                        List.of()));

        mockMvc.perform(get("/v1/impact/pr")
                        .param("serviceId", "svc-001")
                        .param("commitSha", "abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.changedSymbols.length()").value(1))
                .andExpect(jsonPath("$.data.suggestedTestScope[0].type").value("UNIT"))
                .andExpect(jsonPath("$.data.suggestedTestScope[0].exists").value(true));
    }

    @Test
    void impact_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/impact/pr")
                        .param("serviceId", "svc-missing")
                        .param("commitSha", "abc123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void impact_returns404_whenCommitNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(commitValidator.isIndexed("svc-001", "unknown")).thenReturn(false);

        mockMvc.perform(get("/v1/impact/pr")
                        .param("serviceId", "svc-001")
                        .param("commitSha", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void impact_returns400_whenCommitShaMissing() throws Exception {
        mockMvc.perform(get("/v1/impact/pr").param("serviceId", "svc-001"))
                .andExpect(status().isBadRequest());
    }
}

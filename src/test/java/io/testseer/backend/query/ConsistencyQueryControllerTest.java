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

@WebMvcTest(ConsistencyQueryController.class)
class ConsistencyQueryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ConsistencyQueryService consistencyQueryService;
    @MockBean FreshnessResolver freshnessResolver;

    @Test
    void getScenarios_returns404WhenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/consistency/scenarios").param("serviceId", "svc-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void getScenarios_returns200WithScenarioRows() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(consistencyQueryService.query(eq("svc-adapter"), eq("DUAL_WRITE"), isNull()))
                .thenReturn(List.of(new ConsistencyQueryService.ConsistencyScenarioView(
                        "partner-offer-call-recorder-dual-write",
                        "DUAL_WRITE",
                        "HANDLER",
                        "com.example.Handler#recordSubmission",
                        "MARIADB",
                        "PartnerOfferCallRecorder",
                        List.of("partnerId", "offerId"),
                        List.of(),
                        null,
                        List.of(),
                        "INFERRED",
                        0.88,
                        null)));

        mockMvc.perform(get("/v1/consistency/scenarios")
                        .param("serviceId", "svc-adapter")
                        .param("pattern", "DUAL_WRITE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data[0].scenarioId")
                        .value("partner-offer-call-recorder-dual-write"))
                .andExpect(jsonPath("$.data[0].pattern").value("DUAL_WRITE"));
    }
}

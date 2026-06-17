package io.testseer.backend.query;

import io.testseer.backend.api.TestSeerExceptionHandler;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractGapController.class)
@Import(TestSeerExceptionHandler.class)
class ContractGapControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ContractReconciliationService reconciliationService;
    @MockBean ContractTestCoverageGapService testCoverageGapService;
    @MockBean ContractOperationQueryService contractQueryService;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean ServiceRegistryService registryService;

    @Test
    void contractGaps_returns200() throws Exception {
        when(registryService.getById("svc-offer")).thenReturn(new ServiceEntry(
                "svc-offer", "quotient", "platform-optimus-offer-service",
                "platform-optimus-offer-service", "service", "MAVEN",
                List.of("src/main/java"), List.of("src/test/java"),
                null, true, Instant.now(), Instant.now()));
        when(freshnessResolver.resolve(eq("svc-offer"), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(reconciliationService.computeGaps("svc-offer", "Offers")).thenReturn(List.of(
                new ContractReconciliationService.ContractGapView(
                        "CONTRACT_ONLY",
                        "Offers",
                        "GET",
                        "/offers/{offerId}",
                        "/offers/{*}",
                        "Offers|GET|/offers/{*}",
                        "get-offers",
                        null,
                        null,
                        "platform-optimus-offer-service",
                        "OpenAPI operation documented but no matching inbound REST handler indexed")
        ));

        mockMvc.perform(get("/v1/gaps/contract")
                        .param("serviceId", "svc-offer")
                        .param("specDomain", "Offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].gapType").value("CONTRACT_ONLY"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));
    }
}

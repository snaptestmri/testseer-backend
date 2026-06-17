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

@WebMvcTest(ContractQueryController.class)
@Import(TestSeerExceptionHandler.class)
class ContractQueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ContractOperationQueryService contractQueryService;
    @MockBean ContractSchemaQueryService contractSchemaQueryService;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean ServiceRegistryService registryService;

    @Test
    void contractOperations_returns200() throws Exception {
        when(registryService.getById("svc-001")).thenReturn(new ServiceEntry(
                "svc-001", "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                "library", "UNKNOWN", List.of(), List.of(), null, true,
                Instant.now(), Instant.now()));
        when(contractQueryService.catalogServiceIdForFreshness("quotient")).thenReturn("svc-001");
        when(freshnessResolver.resolve(eq("svc-001"), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(contractQueryService.query("svc-001", "Offers")).thenReturn(List.of(
                new ContractOperationQueryService.ContractOperationView(
                        "Offers|POST|/offers/redeem",
                        "Offers",
                        "reference/Offers/Offers-APIs.v1.json",
                        "3.1.0",
                        "post-offers-redeem",
                        "POST",
                        "/offers/redeem",
                        "/offers/redeem",
                        "Redeem Offers",
                        "[]",
                        null,
                        null,
                        "[\"offerIds\"]",
                        "[\"redemptionStatus\"]",
                        "[\"https://apir.receiptiq.com/service\"]",
                        "platform-optimus-offer-service",
                        "OPENAPI",
                        0.95
                )
        ));

        mockMvc.perform(get("/v1/facts/contract-operations")
                        .param("serviceId", "svc-001")
                        .param("specDomain", "Offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operationIdOpenapi").value("post-offers-redeem"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));
    }
}

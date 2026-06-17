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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractGraphController.class)
@Import(TestSeerExceptionHandler.class)
class ContractGraphControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ContractEntryLinkService linkService;
    @MockBean ContractOperationQueryService contractQueryService;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean ServiceRegistryService registryService;

    @Test
    void linkedContractOperations_returns200() throws Exception {
        when(registryService.getById("svc-001")).thenReturn(catalogService());
        when(contractQueryService.catalogServiceIdForFreshness("quotient")).thenReturn("svc-001");
        when(freshnessResolver.resolve(eq("svc-001"), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(linkService.enrichOperations("svc-001", "Offers")).thenReturn(List.of(
                new ContractEntryLinkService.ContractOperationLinkedView(
                        new ContractOperationQueryService.ContractOperationView(
                                "Offers|POST|/offers/redeem", "Offers", null, null,
                                "post-offers-redeem", "POST", "/offers/redeem", "/offers/redeem",
                                null, null, null, null, null, null, null, null, null, 0.9),
                        new ContractEntryLinkService.EntryTriggerLink(
                                "impl-1", "platform-optimus-offer-service", "trg-1",
                                "REST_INBOUND", "com.example.RedeemController", "redeem",
                                "/offers/redeem", "MATCHED")
                )
        ));

        mockMvc.perform(get("/v1/facts/contract-operations/linked")
                        .param("serviceId", "svc-001")
                        .param("specDomain", "Offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].link.linkStatus").value("MATCHED"));
    }

    @Test
    void contractEntryFlow_returns404WhenOperationMissing() throws Exception {
        when(registryService.getById("svc-001")).thenReturn(catalogService());
        when(contractQueryService.catalogServiceIdForFreshness("quotient")).thenReturn("svc-001");
        when(freshnessResolver.resolve(eq("svc-001"), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(linkService.traceContractEntryFlow(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/graph/contract-entry-flow")
                        .param("serviceId", "svc-001")
                        .param("operationId", "missing"))
                .andExpect(status().isNotFound());
    }

    private static ServiceEntry catalogService() {
        return new ServiceEntry(
                "svc-001", "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                "library", "UNKNOWN", List.of(), List.of(), null, true,
                Instant.now(), Instant.now());
    }
}

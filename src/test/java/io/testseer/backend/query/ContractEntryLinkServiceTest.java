package io.testseer.backend.query;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ContractEntryLinkServiceTest {

    @Mock JdbcClient db;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    JdbcClient.StatementSpec statementSpec;
    @Mock ServiceRegistryService registryService;
    @Mock ContractOperationQueryService contractQueryService;
    @Mock EntryFlowService entryFlowService;

    @InjectMocks ContractEntryLinkService linkService;

    @BeforeEach
    void stubDb() {
        lenient().when(db.sql(anyString())).thenReturn(statementSpec);
        lenient().when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
    }

    private ContractOperationQueryService.ContractOperationView offerRedeem() {
        return new ContractOperationQueryService.ContractOperationView(
                "Offers|POST|/offers/redeem",
                "Offers",
                "reference/Offers/Offers-APIs.v1.json",
                "3.1.0",
                "post-offers-redeem",
                "POST",
                "/offers/redeem",
                "/offers/redeem",
                "Redeem",
                "[]",
                null, null, "[]", "[]", "[]",
                "platform-optimus-offer-service",
                "OPENAPI",
                0.95
        );
    }

    @Test
    void resolveLink_returnsImplementationNotIndexedWhenNoTriggers() {
        ServiceEntry querySvc = new ServiceEntry(
                "catalog", "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                "library", "UNKNOWN", List.of(), List.of(), null, true, Instant.now(), Instant.now());
        ServiceEntry implSvc = new ServiceEntry(
                "offer", "quotient", "platform-optimus-offer-service", "platform-optimus-offer-service",
                "service", "MAVEN", List.of("src/main/java"), List.of(), null, true, Instant.now(), Instant.now());

        when(registryService.getById("catalog")).thenReturn(querySvc);
        when(registryService.getByOrgAndName("quotient", "platform-optimus-offer-service"))
                .thenReturn(Optional.of(implSvc));
        when(statementSpec.query(String.class).optional()).thenReturn(Optional.empty());

        ContractEntryLinkService.EntryTriggerLink link =
                linkService.resolveLink("catalog", offerRedeem());

        assertThat(link.linkStatus()).isEqualTo("IMPLEMENTATION_NOT_INDEXED");
        assertThat(link.implementingServiceId()).isEqualTo("offer");
    }

    @Test
    void enrichOperations_wrapsEachOperationWithLink() {
        when(contractQueryService.query("catalog", "Offers"))
                .thenReturn(List.of(offerRedeem()));
        when(registryService.getById("catalog")).thenReturn(new ServiceEntry(
                "catalog", "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                "library", "UNKNOWN", List.of(), List.of(), null, true, Instant.now(), Instant.now()));
        when(registryService.getByOrgAndName(eq("quotient"), anyString())).thenReturn(Optional.empty());

        List<ContractEntryLinkService.ContractOperationLinkedView> linked =
                linkService.enrichOperations("catalog", "Offers");

        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).operation().operationIdOpenapi()).isEqualTo("post-offers-redeem");
        assertThat(linked.get(0).link().linkStatus()).isEqualTo("SERVICE_NOT_REGISTERED");
    }
}

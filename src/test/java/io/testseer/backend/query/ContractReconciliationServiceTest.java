package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractReconciliationServiceTest {

    @Test
    void reconcile_detectsContractOnlyAndImplementationOnly() {
        List<ContractReconciliationService.ContractSide> contracts = List.of(
                side("Offers|POST|/offers/redeem", "post-offers-redeem", "Offers",
                        "POST", "/offers/redeem", "/offers/redeem"),
                side("Offers|GET|/offers/{*}", "get-offers", "Offers",
                        "GET", "/offers/{offerId}", "/offers/{*}")
        );
        List<ContractReconciliationService.ImplementationSide> implementations = List.of(
                impl("POST", "/offers/redeem",
                        "com.example.RedeemController", "redeem"),
                impl("POST", "/internal/health", null, "health")
        );

        List<ContractReconciliationService.ContractGapView> gaps =
                ContractReconciliationService.reconcile(
                        contracts, implementations, "platform-optimus-offer-service");

        assertThat(gaps).extracting(ContractReconciliationService.ContractGapView::gapType)
                .containsExactlyInAnyOrder("CONTRACT_ONLY", "IMPLEMENTATION_ONLY");

        assertThat(gaps).filteredOn(g -> "CONTRACT_ONLY".equals(g.gapType()))
                .singleElement()
                .satisfies(g -> {
                    assertThat(g.operationIdOpenapi()).isEqualTo("get-offers");
                    assertThat(g.pathNormalized()).isEqualTo("/offers/{*}");
                });

        assertThat(gaps).filteredOn(g -> "IMPLEMENTATION_ONLY".equals(g.gapType()))
                .singleElement()
                .satisfies(g -> {
                    assertThat(g.pathTemplate()).isEqualTo("/internal/health");
                    assertThat(g.linkedHandlerFqn()).isNull();
                });
    }

    @Test
    void reconcile_matchesOnMethodAndNormalizedPath() {
        List<ContractReconciliationService.ContractSide> contracts = List.of(
                side("Offers|POST|/offers/redeem", "post-offers-redeem", "Offers",
                        "POST", "/offers/redeem", "/offers/redeem")
        );
        List<ContractReconciliationService.ImplementationSide> implementations = List.of(
                impl("POST", "/offers/redeem",
                        "com.example.RedeemController", "redeem")
        );

        assertThat(ContractReconciliationService.reconcile(
                contracts, implementations, "platform-optimus-offer-service")).isEmpty();
    }

    @Test
    void normalizeInboundPath_alignsWithOpenApiPathParams() {
        assertThat(ContractReconciliationService.normalizeInboundPath("/offers/{offerId}/detail"))
                .isEqualTo("/offers/{*}/detail");
    }

    private static ContractReconciliationService.ContractSide side(
            String operationId,
            String operationIdOpenapi,
            String specDomain,
            String method,
            String pathTemplate,
            String pathNormalized) {
        return new ContractReconciliationService.ContractSide(
                operationId, operationIdOpenapi, specDomain, method,
                pathTemplate, pathNormalized, "platform-optimus-offer-service");
    }

    private static ContractReconciliationService.ImplementationSide impl(
            String method, String path, String handlerFqn, String handlerMethod) {
        return new ContractReconciliationService.ImplementationSide(
                "trigger-1", "REST_INBOUND", method, path,
                ContractReconciliationService.normalizeInboundPath(path),
                handlerFqn, handlerMethod);
    }
}

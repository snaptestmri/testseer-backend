package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTestCoverageGapServiceTest {

    @Test
    void reconcile_contractUntestedAndTestUndocumented() {
        List<ContractTestCoverageGapService.ContractOperationSide> contracts = List.of(
                new ContractTestCoverageGapService.ContractOperationSide(
                        "Offers|POST|/offers/redeem",
                        "Offers",
                        "POST",
                        "/offers/redeem",
                        "/offers/redeem",
                        "platform-optimus-offer-service"),
                new ContractTestCoverageGapService.ContractOperationSide(
                        "Offers|POST|/offers/all",
                        "Offers",
                        "POST",
                        "/offers/all",
                        "/offers/all",
                        "platform-optimus-offer-service")
        );

        List<ContractTestCoverageGapService.TestCallSide> tests = List.of(
                new ContractTestCoverageGapService.TestCallSide(
                        "OfferTest.java",
                        "com.example.OfferTest",
                        "POST",
                        "/offers/redeem",
                        "/offers/redeem",
                        "OFFER_REDEEM"),
                new ContractTestCoverageGapService.TestCallSide(
                        "UserTest.java",
                        "com.example.UserTest",
                        "GET",
                        "/user/profile",
                        "/user/profile",
                        "USER_PROFILE")
        );

        List<ContractTestCoverageGapService.ContractTestCoverageGapView> gaps =
                ContractTestCoverageGapService.reconcile(
                        contracts, tests, "platform-optimus-offer-service");

        assertThat(gaps).extracting(ContractTestCoverageGapService.ContractTestCoverageGapView::gapType)
                .containsExactlyInAnyOrder("CONTRACT_UNTESTED", "TEST_UNDOCUMENTED");
        assertThat(gaps.stream()
                .filter(g -> "CONTRACT_UNTESTED".equals(g.gapType()))
                .findFirst()
                .orElseThrow()
                .pathNormalized()).isEqualTo("/offers/all");
    }
}

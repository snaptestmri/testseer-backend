package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Query — Contract Gaps", description = "OpenAPI contract vs Java inbound REST reconciliation (BL-046 P2)")
@RestController
public class ContractGapController {

    private final ContractReconciliationService reconciliationService;
    private final ContractTestCoverageGapService testCoverageGapService;
    private final ContractOperationQueryService contractQueryService;
    private final FreshnessResolver freshnessResolver;
    private final ServiceRegistryService registryService;
    private final int staleThresholdMinutes;

    public ContractGapController(
            ContractReconciliationService reconciliationService,
            ContractTestCoverageGapService testCoverageGapService,
            ContractOperationQueryService contractQueryService,
            FreshnessResolver freshnessResolver,
            ServiceRegistryService registryService,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.reconciliationService = reconciliationService;
        this.testCoverageGapService = testCoverageGapService;
        this.contractQueryService = contractQueryService;
        this.freshnessResolver = freshnessResolver;
        this.registryService = registryService;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Contract vs implementation gaps",
               description = "Compares riq-platform-apis-optimus operations to indexed inbound REST handlers. "
                       + "Gap types: CONTRACT_ONLY (documented, not implemented), "
                       + "IMPLEMENTATION_ONLY (handler, not in contract).")
    @GetMapping("/v1/gaps/contract")
    public ResponseEntity<ResponseEnvelope<List<ContractReconciliationService.ContractGapView>>> getContractGaps(
            @RequestParam String serviceId,
            @RequestParam(required = false) String specDomain) {

        FreshnessStatus status = resolveFreshness(serviceId);
        List<ContractReconciliationService.ContractGapView> data =
                reconciliationService.computeGaps(serviceId, specDomain);
        return FreshnessHttp.respond(status, data);
    }

    @Operation(summary = "Contract vs REST-Assured test coverage gaps",
               description = "Compares OpenAPI contract operations to HTTP calls extracted from riq-qa-REST-Assured. "
                       + "Gap types: CONTRACT_UNTESTED, TEST_UNDOCUMENTED.")
    @GetMapping("/v1/gaps/contract-test-coverage")
    public ResponseEntity<ResponseEnvelope<List<ContractTestCoverageGapService.ContractTestCoverageGapView>>> getContractTestCoverageGaps(
            @RequestParam String serviceId,
            @RequestParam(required = false) String testServiceId,
            @RequestParam(required = false) String specDomain) {

        FreshnessStatus status = resolveFreshness(serviceId);
        List<ContractTestCoverageGapService.ContractTestCoverageGapView> data =
                testCoverageGapService.computeGaps(serviceId, testServiceId, specDomain);
        return FreshnessHttp.respond(status, data);
    }

    private FreshnessStatus resolveFreshness(String serviceId) {
        ServiceEntry svc = registryService.getById(serviceId);
        if (isApisCatalogService(svc)) {
            String catalogId = contractQueryService.catalogServiceIdForFreshness(svc.orgId());
            if (catalogId == null) {
                return FreshnessStatus.NOT_INDEXED;
            }
            return freshnessResolver.resolve(catalogId, staleThresholdMinutes);
        }
        return freshnessResolver.resolve(serviceId, staleThresholdMinutes);
    }

    private static boolean isApisCatalogService(ServiceEntry svc) {
        return "library".equalsIgnoreCase(svc.moduleType())
                && svc.repo() != null
                && svc.repo().contains("apis-optimus");
    }
}

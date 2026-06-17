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

@Tag(name = "Query — Contract Entry Flow", description = "Contract operation to inbound handler entry-flow trace (BL-046 P3)")
@RestController
public class ContractGraphController {

    private final ContractEntryLinkService linkService;
    private final ContractOperationQueryService contractQueryService;
    private final FreshnessResolver freshnessResolver;
    private final ServiceRegistryService registryService;
    private final int staleThresholdMinutes;

    public ContractGraphController(
            ContractEntryLinkService linkService,
            ContractOperationQueryService contractQueryService,
            FreshnessResolver freshnessResolver,
            ServiceRegistryService registryService,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.linkService = linkService;
        this.contractQueryService = contractQueryService;
        this.freshnessResolver = freshnessResolver;
        this.registryService = registryService;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Contract operations with entry-trigger links",
               description = "Same as contract-operations plus linked inbound handler when implementation is indexed.")
    @GetMapping("/v1/facts/contract-operations/linked")
    public ResponseEntity<ResponseEnvelope<List<ContractEntryLinkService.ContractOperationLinkedView>>> getLinkedContractOperations(
            @RequestParam String serviceId,
            @RequestParam(required = false) String specDomain) {

        FreshnessStatus status = resolveFreshness(serviceId);
        List<ContractEntryLinkService.ContractOperationLinkedView> data =
                linkService.enrichOperations(serviceId, specDomain);
        return FreshnessHttp.respond(status, data);
    }

    @Operation(summary = "Trace entry flow from a contract operation",
               description = "Resolves OpenAPI operation → implementing service inbound trigger → handler data access and gates.")
    @GetMapping("/v1/graph/contract-entry-flow")
    public ResponseEntity<ResponseEnvelope<ContractEntryLinkService.ContractEntryFlowReport>> traceContractEntryFlow(
            @RequestParam String serviceId,
            @RequestParam(required = false) String operationId,
            @RequestParam(required = false) String specDomain,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(required = false) String path,
            @RequestParam(required = false, defaultValue = "unknown") String env) {

        FreshnessStatus status = resolveFreshness(serviceId);
        return linkService.traceContractEntryFlow(serviceId, operationId, specDomain, httpMethod, path, env)
                .map(report -> FreshnessHttp.respond(status, report))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ResponseEnvelope.notIndexed()));
    }

    private FreshnessStatus resolveFreshness(String serviceId) {
        ServiceEntry svc = registryService.getById(serviceId);
        String catalogServiceId = contractQueryService.catalogServiceIdForFreshness(svc.orgId());
        if (catalogServiceId == null) {
            return freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        }
        return freshnessResolver.resolve(catalogServiceId, staleThresholdMinutes);
    }
}

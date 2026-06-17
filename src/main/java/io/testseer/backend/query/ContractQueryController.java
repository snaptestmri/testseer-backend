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

@Tag(name = "Query — Contract Operations", description = "Partner API contract inventory from riq-platform-apis-optimus (BL-046)")
@RestController
public class ContractQueryController {

    private final ContractOperationQueryService contractQueryService;
    private final ContractSchemaQueryService contractSchemaQueryService;
    private final FreshnessResolver freshnessResolver;
    private final ServiceRegistryService registryService;
    private final int staleThresholdMinutes;

    public ContractQueryController(
            ContractOperationQueryService contractQueryService,
            ContractSchemaQueryService contractSchemaQueryService,
            FreshnessResolver freshnessResolver,
            ServiceRegistryService registryService,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.contractQueryService = contractQueryService;
        this.contractSchemaQueryService = contractSchemaQueryService;
        this.freshnessResolver = freshnessResolver;
        this.registryService = registryService;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Partner API contract operations",
               description = "OpenAPI operations indexed from riq-platform-apis-optimus. "
                       + "Filter by catalog library serviceId or implementing serviceId, optional specDomain.")
    @GetMapping("/v1/facts/contract-operations")
    public ResponseEntity<ResponseEnvelope<List<ContractOperationQueryService.ContractOperationView>>> getContractOperations(
            @RequestParam String serviceId,
            @RequestParam(required = false) String specDomain) {

        FreshnessStatus status = resolveFreshness(serviceId);
        List<ContractOperationQueryService.ContractOperationView> data =
                contractQueryService.query(serviceId, specDomain);
        return FreshnessHttp.respond(status, data);
    }

    @Operation(summary = "Partner API contract JSON schemas",
               description = "JSON schema summaries indexed from riq-platform-apis-optimus, including nested field paths.")
    @GetMapping("/v1/facts/contract-schemas")
    public ResponseEntity<ResponseEnvelope<List<ContractSchemaQueryService.ContractSchemaView>>> getContractSchemas(
            @RequestParam String serviceId,
            @RequestParam(required = false) String schemaId) {

        FreshnessStatus status = resolveFreshness(serviceId);
        List<ContractSchemaQueryService.ContractSchemaView> data =
                contractSchemaQueryService.query(serviceId, schemaId);
        return FreshnessHttp.respond(status, data);
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

package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Query — Data Object Catalog", description = "Persistence entity/document catalog from library indexes (WRK-18)")
@RestController
@RequestMapping("/v1/catalog")
public class CatalogQueryController {

    private final CatalogQueryService catalogQueryService;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public CatalogQueryController(
            CatalogQueryService catalogQueryService,
            FreshnessResolver freshnessResolver,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.catalogQueryService = catalogQueryService;
        this.freshnessResolver = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Data object catalog (entity/document → store → physical name)")
    @GetMapping("/data-objects")
    public ResponseEntity<ResponseEnvelope<PageResult<CatalogQueryService.DataObjectView>>> getDataObjects(
            @RequestParam String serviceId,
            @RequestParam(required = false) String storeType,
            @RequestParam(required = false) String physicalName,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {

        PageParams.Validated page = PageParams.validate(limit, offset);
        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(
                status,
                catalogQueryService.queryDataObjects(
                        serviceId, storeType, physicalName, page.limit(), page.offset()));
    }

    @Operation(summary = "DDL schema objects indexed from riq-platform-db (Phase 5)")
    @GetMapping("/schema-objects")
    public ResponseEntity<ResponseEnvelope<PageResult<CatalogQueryService.SchemaObjectView>>> getSchemaObjects(
            @RequestParam String serviceId,
            @RequestParam(required = false) String storeType,
            @RequestParam(required = false) String physicalName,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {

        PageParams.Validated page = PageParams.validate(limit, offset);
        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(
                status,
                catalogQueryService.querySchemaObjects(
                        serviceId, storeType, physicalName, page.limit(), page.offset()));
    }
}

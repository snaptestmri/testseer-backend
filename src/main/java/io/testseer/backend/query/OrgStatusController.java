package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@Tag(name = "Query — Org Status", description = "Portfolio-wide indexing freshness summary")
@RestController
@RequestMapping("/v1/status")
public class OrgStatusController {

    private final ServiceRegistryService registryService;
    private final FreshnessResolver freshnessResolver;
    private final JdbcClient db;
    private final int staleThresholdMinutes;

    public OrgStatusController(ServiceRegistryService registryService,
                               FreshnessResolver freshnessResolver,
                               JdbcClient db,
                               @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.registryService = registryService;
        this.freshnessResolver = freshnessResolver;
        this.db = db;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Org-wide service freshness summary",
               description = "Lists registered services with freshness status and latest job metadata.")
    @ApiResponse(responseCode = "200", description = "Summary returned")
    @GetMapping
    public ResponseEntity<OrgStatusSummary> orgStatus(
            @Parameter(description = "Filter by organization id")
            @RequestParam(required = false) String orgId) {

        List<ServiceEntry> services = registryService.listAll().stream()
                .filter(s -> s.enabled())
                .filter(s -> orgId == null || orgId.isBlank() || s.orgId().equals(orgId))
                .sorted(Comparator.comparing(ServiceEntry::orgId).thenComparing(ServiceEntry::repo))
                .toList();

        String summaryOrg = orgId != null && !orgId.isBlank()
                ? orgId
                : (services.isEmpty() ? "all" : services.getFirst().orgId());

        List<OrgServiceStatus> rows = services.stream()
                .map(this::toOrgServiceStatus)
                .toList();

        return ResponseEntity.ok(new OrgStatusSummary(summaryOrg, rows.size(), rows));
    }

    private OrgServiceStatus toOrgServiceStatus(ServiceEntry svc) {
        FreshnessStatus freshness = freshnessResolver.resolve(svc.serviceId(), staleThresholdMinutes);

        return db.sql("""
                SELECT ar.job_id, ar.status, ar.commit_sha
                FROM analysis_runs ar
                WHERE ar.service_id = :svcId
                ORDER BY ar.enqueued_at DESC
                LIMIT 1
                """)
                .param("svcId", svc.serviceId())
                .query((rs, row) -> new OrgServiceStatus(
                        svc.serviceId(),
                        svc.orgId(),
                        svc.repo(),
                        svc.serviceName(),
                        freshness,
                        rs.getString("commit_sha"),
                        rs.getString("job_id"),
                        rs.getString("status")
                ))
                .optional()
                .orElseGet(() -> new OrgServiceStatus(
                        svc.serviceId(), svc.orgId(), svc.repo(), svc.serviceName(),
                        freshness, null, null, null
                ));
    }
}

package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.ingestion.maven.MavenLinkBackfillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Maven", description = "Maven artifact link maintenance")
@RestController
@RequestMapping("/admin/maven")
public class MavenLinkBackfillController {

    private final MavenLinkBackfillService backfillService;

    public MavenLinkBackfillController(MavenLinkBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Operation(summary = "Backfill maven dependency linkedServiceId",
               description = """
                   Re-runs InternalArtifactLinker against existing maven_dependency_facts for the latest
                   commit per service (or one serviceId). Updates linked_service_id, link_source, cross_repo
                   without a full re-index. Optionally syncs OWNED_BY graph edges (ARTIFACT → SERVICE).""")
    @PostMapping("/backfill-links")
    public MavenLinkBackfillResponse backfillLinks(@Valid @RequestBody MavenLinkBackfillRequest request) {
        return backfillService.backfill(request);
    }
}

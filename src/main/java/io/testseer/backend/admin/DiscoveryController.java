package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin — Discovery", description = "Auto-register services by scanning a GitHub organisation")
@RestController
@RequestMapping("/admin/discover")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Operation(
        summary = "Discover and register all Java services in a GitHub org",
        description = """
            Scans every non-archived, non-fork repository in the organisation. \
            Repositories containing a pom.xml (MAVEN) or build.gradle (GRADLE) \
            at the root are auto-registered in the service registry. \
            Already-registered services are reported in alreadyKnown and are \
            not modified. Non-Java repos are reported in skipped. \
            Runs synchronously — large organisations may take several seconds.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Discovery complete",
            content = @Content(schema = @Schema(implementation = DiscoveryResult.class))),
        @ApiResponse(responseCode = "400", description = "orgId parameter is missing")
    })
    @PostMapping
    public DiscoveryResult discover(
            @Parameter(description = "GitHub organisation login (e.g. 'acme')", required = true)
            @RequestParam String orgId) {
        return discoveryService.discover(orgId);
    }
}

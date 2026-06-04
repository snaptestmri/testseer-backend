package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin — Indexing", description = "Trigger on-demand indexing of Java services")
@RestController
@RequestMapping("/admin/index/local")
public class LocalIndexTriggerController {

    private final LocalIndexTriggerService triggerService;

    public LocalIndexTriggerController(LocalIndexTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @Operation(
        summary = "Index a local folder",
        description = """
            Indexes all `.java` files found recursively under the given server-accessible \
            directory path. If the service is not yet registered, it is auto-registered \
            using the directory name as the service name and detecting the build tool \
            from `pom.xml` or `build.gradle`. The git commit SHA is resolved from the \
            directory's git history; if the path is not a git repository, a \
            `local-{epoch}` pseudo-SHA is used. Runs synchronously — returns when \
            indexing is complete. Use `GET /v1/status/{serviceId}` to verify freshness \
            afterwards.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Indexing complete",
            content = @Content(schema = @Schema(implementation = LocalIndexTriggerResponse.class))),
        @ApiResponse(responseCode = "400", description = "Path is not a directory or request fields are blank",
            content = @Content(schema = @Schema(type = "string", example = "Path is not a directory: /bad/path")))
    })
    @PostMapping
    public LocalIndexTriggerResponse trigger(
            @Valid @RequestBody LocalIndexTriggerRequest request) {
        return triggerService.trigger(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadPath(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}

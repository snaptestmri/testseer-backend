package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.registry.ServiceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin — Indexing", description = "Trigger on-demand indexing of Java services")
@RestController
@RequestMapping("/admin/index")
public class IndexTriggerController {

    private final IndexTriggerService triggerService;

    public IndexTriggerController(IndexTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @Operation(
        summary = "Trigger on-demand indexing for a registered service",
        description = """
            Fetches all .java files from GitHub using the Git Trees API and publishes an \
            analysis job to Kafka. The job runs asynchronously — this endpoint returns \
            immediately with a jobId. Track progress via GET /v1/status/{serviceId}. \
            Returns 409 if a QUEUED or RUNNING job already exists for the service.""")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Job queued successfully",
            content = @Content(schema = @Schema(implementation = IndexTriggerResponse.class))),
        @ApiResponse(responseCode = "404", description = "Service not found"),
        @ApiResponse(responseCode = "409", description = "A job is already in progress for this service")
    })
    @PostMapping("/{serviceId}")
    public ResponseEntity<IndexTriggerResponse> trigger(
            @Parameter(description = "Unique service identifier", required = true)
            @PathVariable String serviceId,
            @RequestBody(required = false) IndexTriggerRequest request) {

        IndexTriggerRequest req = request != null ? request : new IndexTriggerRequest(null);
        return ResponseEntity.accepted().body(triggerService.trigger(serviceId, req));
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<Void> handleNotFound(ServiceNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(JobAlreadyInFlightException.class)
    public ResponseEntity<String> handleConflict(JobAlreadyInFlightException ex) {
        return ResponseEntity.status(409).body(ex.getMessage());
    }
}

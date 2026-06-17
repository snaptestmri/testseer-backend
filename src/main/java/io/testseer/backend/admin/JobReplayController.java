package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Jobs", description = "Replay failed ingestion jobs")
@RestController
@RequestMapping("/admin/jobs")
public class JobReplayController {

    private final JobReplayService replayService;

    public JobReplayController(JobReplayService replayService) {
        this.replayService = replayService;
    }

    @Operation(summary = "Replay a DLQ ingestion job",
               description = "Re-queues a new MANUAL index job for the same service and commit as the DLQ job.")
    @ApiResponse(responseCode = "202", description = "Replay job queued")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job is not in DLQ status")
    @PostMapping("/{jobId}/replay")
    public ResponseEntity<IndexTriggerResponse> replay(@PathVariable String jobId) {
        return ResponseEntity.accepted().body(replayService.replay(jobId));
    }
}

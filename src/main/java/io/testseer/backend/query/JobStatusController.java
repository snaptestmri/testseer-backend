package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;

@Tag(name = "Query — Jobs", description = "Ingestion job lifecycle status")
@RestController
@RequestMapping("/v1/jobs")
public class JobStatusController {

    private final JdbcClient db;

    public JobStatusController(JdbcClient db) {
        this.db = db;
    }

    @Operation(summary = "Get ingestion job status by jobId",
               description = "Returns analysis_runs lifecycle fields for observability and debugging.")
    @ApiResponse(responseCode = "200", description = "Job found")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJob(@PathVariable String jobId) {
        return db.sql("""
                SELECT job_id, org_id, service_id, commit_sha, job_type, status, attempt,
                       enqueued_at, started_at, completed_at, error_detail
                FROM analysis_runs WHERE job_id = :id
                """)
                .param("id", jobId)
                .query((rs, row) -> new JobStatusResponse(
                        rs.getString("job_id"),
                        rs.getString("org_id"),
                        rs.getString("service_id"),
                        rs.getString("commit_sha"),
                        rs.getString("job_type"),
                        rs.getString("status"),
                        rs.getInt("attempt"),
                        toInstant(rs.getTimestamp("enqueued_at")),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("completed_at")),
                        rs.getString("error_detail")
                ))
                .optional()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}

package io.testseer.backend.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class JobReplayService {

    private final JdbcClient db;
    private final IndexTriggerService triggerService;

    public JobReplayService(JdbcClient db, IndexTriggerService triggerService) {
        this.db = db;
        this.triggerService = triggerService;
    }

    public IndexTriggerResponse replay(String jobId) {
        RunRow run = db.sql("""
                SELECT service_id, commit_sha, status FROM analysis_runs WHERE job_id = :id
                """)
                .param("id", jobId)
                .query((rs, row) -> new RunRow(
                        rs.getString("service_id"),
                        rs.getString("commit_sha"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new JobNotFoundException(jobId));

        if (!"DLQ".equals(run.status())) {
            throw new JobNotReplayableException(jobId, run.status());
        }

        return triggerService.trigger(run.serviceId(), new IndexTriggerRequest(run.commitSha()));
    }

    private record RunRow(String serviceId, String commitSha, String status) {}
}

package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AnalysisRunTracker {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunTracker.class);

    private final JdbcClient db;

    public AnalysisRunTracker(JdbcClient db) {
        this.db = db;
    }

    public void markQueued(IngestionJob job) {
        db.sql("""
                INSERT INTO analysis_runs
                  (job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
                VALUES (:jobId, :orgId, :serviceId, :commitSha, :jobType, 'QUEUED', :attempt, :enqueuedAt)
                ON CONFLICT (job_id) DO NOTHING
                """)
                .param("jobId",       job.jobId())
                .param("orgId",       job.orgId())
                .param("serviceId",   job.serviceId())
                .param("commitSha",   job.commitSha())
                .param("jobType",     job.jobType())
                .param("attempt",     job.attempt())
                .param("enqueuedAt",  job.enqueuedAt())
                .update();
    }

    public void markRunning(String jobId) {
        db.sql("UPDATE analysis_runs SET status = 'RUNNING', started_at = now() WHERE job_id = :id")
                .param("id", jobId)
                .update();
    }

    public void markComplete(String jobId) {
        db.sql("UPDATE analysis_runs SET status = 'COMPLETE', completed_at = now() WHERE job_id = :id")
                .param("id", jobId)
                .update();
    }

    public void markFailed(String jobId, String errorDetail) {
        db.sql("""
                UPDATE analysis_runs
                SET status = 'FAILED', completed_at = now(), error_detail = :detail
                WHERE job_id = :id
                """)
                .param("id",     jobId)
                .param("detail", errorDetail)
                .update();
    }
}

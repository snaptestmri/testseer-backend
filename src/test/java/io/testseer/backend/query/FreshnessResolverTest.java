package io.testseer.backend.query;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FreshnessResolverTest extends AbstractIntegrationTest {
    @Autowired FreshnessResolver resolver;
    @Autowired JdbcClient db;

    @BeforeEach
    void cleanup() {
        IntegrationTestDb.clearCoreFacts(db);
        db.sql("""
            INSERT INTO service_registry(service_id, org_id, repo, service_name, build_tool, enabled)
            VALUES ('svc-001', 'acme', 'repo', 'orders', 'MAVEN', true)
            """).update();
    }

    @Test
    void notIndexed_whenNoRuns() {
        FreshnessStatus status = resolver.resolve("svc-001", 60);
        assertThat(status).isEqualTo(FreshnessStatus.NOT_INDEXED);
    }

    @Test
    void indexing_whenJobRunning() {
        db.sql("""
            INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
            VALUES ('job-001', 'acme', 'svc-001', 'abc', 'PR', 'RUNNING', 1, now())
            """).update();

        assertThat(resolver.resolve("svc-001", 60)).isEqualTo(FreshnessStatus.INDEXING);
    }

    @Test
    void current_whenRecentlyCompleted() {
        Instant recent = Instant.now().minus(10, ChronoUnit.MINUTES);
        db.sql("""
            INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha, job_type, status,
                                      attempt, enqueued_at, completed_at)
            VALUES ('job-002', 'acme', 'svc-001', 'abc', 'PR', 'COMPLETE', 1, :e, :c)
            """)
            .param("e", Timestamp.from(recent))
            .param("c", Timestamp.from(recent))
            .update();

        assertThat(resolver.resolve("svc-001", 60)).isEqualTo(FreshnessStatus.CURRENT);
    }

    @Test
    void stale_whenCompletedBeyondThreshold() {
        Instant old = Instant.now().minus(90, ChronoUnit.MINUTES);
        db.sql("""
            INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha, job_type, status,
                                      attempt, enqueued_at, completed_at)
            VALUES ('job-003', 'acme', 'svc-001', 'abc', 'PR', 'COMPLETE', 1, :e, :c)
            """)
            .param("e", Timestamp.from(old))
            .param("c", Timestamp.from(old))
            .update();

        assertThat(resolver.resolve("svc-001", 60)).isEqualTo(FreshnessStatus.STALE);
    }
}

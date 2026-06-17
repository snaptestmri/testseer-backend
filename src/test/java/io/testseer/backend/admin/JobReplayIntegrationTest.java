package io.testseer.backend.admin;

import io.testseer.backend.KafkaTestConfiguration;
import io.testseer.backend.ingestion.GitHubTreeFetcher;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(KafkaTestConfiguration.class)
class JobReplayIntegrationTest extends io.testseer.backend.AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient db;
    @Autowired KafkaJobPublisher publisher;

    @MockBean GitHubTreeFetcher treeFetcher;

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();
        db.sql("""
                INSERT INTO service_registry(service_id, org_id, repo, service_name, build_tool, enabled)
                VALUES ('svc-1', 'quotient', 'repo', 'svc', 'maven', true)
                """).update();
        db.sql("""
                INSERT INTO analysis_runs
                  (job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at, error_detail)
                VALUES ('job-dlq-1', 'quotient', 'svc-1', 'sha1', 'PR', 'DLQ', 3, :enq, 'parse failed')
                """)
                .param("enq", Timestamp.from(Instant.parse("2026-06-12T10:00:00Z")))
                .update();

        when(treeFetcher.fetchJavaPaths("quotient", "repo", "sha1"))
                .thenReturn(List.of("src/main/java/Foo.java"));
    }

    @Test
    void replay_dlqJob_returns202AndPublishesManualJob() throws Exception {
        mockMvc.perform(post("/admin/jobs/job-dlq-1/replay"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.serviceId").value("svc-1"))
                .andExpect(jsonPath("$.commitSha").value("sha1"))
                .andExpect(jsonPath("$.fileCount").value(1));

        ArgumentCaptor<IngestionJob> captor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(publisher).publishBatchJob(captor.capture());
        IngestionJob job = captor.getValue();
        assertThat(job.jobType()).isEqualTo("MANUAL");
        assertThat(job.serviceId()).isEqualTo("svc-1");
        assertThat(job.commitSha()).isEqualTo("sha1");
        assertThat(job.jobId()).isNotEqualTo("job-dlq-1");
    }

    @Test
    void replay_nonDlqJob_returns409() throws Exception {
        db.sql("UPDATE analysis_runs SET status = 'COMPLETE' WHERE job_id = 'job-dlq-1'").update();

        mockMvc.perform(post("/admin/jobs/job-dlq-1/replay"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void replay_missingJob_returns404() throws Exception {
        mockMvc.perform(post("/admin/jobs/missing-job/replay"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}

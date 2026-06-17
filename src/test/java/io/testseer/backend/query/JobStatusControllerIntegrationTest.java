package io.testseer.backend.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class JobStatusControllerIntegrationTest extends io.testseer.backend.AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient db;

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
    }

    @Test
    void getJob_returnsRunDetails() throws Exception {
        mockMvc.perform(get("/v1/jobs/job-dlq-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-dlq-1"))
                .andExpect(jsonPath("$.status").value("DLQ"))
                .andExpect(jsonPath("$.attempt").value(3))
                .andExpect(jsonPath("$.errorDetail").value("parse failed"));
    }

    @Test
    void getJob_returns404WhenMissing() throws Exception {
        mockMvc.perform(get("/v1/jobs/missing"))
                .andExpect(status().isNotFound());
    }
}

package io.testseer.backend.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class FreshnessResolverTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired FreshnessResolver resolver;
    @Autowired JdbcClient db;

    @BeforeEach
    void cleanup() {
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();
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
            .param("e", recent)
            .param("c", recent)
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
            .param("e", old)
            .param("c", old)
            .update();

        assertThat(resolver.resolve("svc-001", 60)).isEqualTo(FreshnessStatus.STALE);
    }
}

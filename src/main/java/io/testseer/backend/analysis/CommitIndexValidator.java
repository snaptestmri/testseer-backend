package io.testseer.backend.analysis;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class CommitIndexValidator {

    private final JdbcClient db;

    public CommitIndexValidator(JdbcClient db) {
        this.db = db;
    }

    public boolean isIndexed(String serviceId, String commitSha) {
        return db.sql("""
                SELECT 1 FROM analysis_runs
                WHERE service_id = :svcId AND commit_sha = :sha AND status = 'COMPLETE'
                LIMIT 1
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Optional<RunMeta> runMetaForCommit(String serviceId, String commitSha) {
        return db.sql("""
                SELECT completed_at, commit_sha FROM analysis_runs
                WHERE service_id = :svcId AND commit_sha = :sha AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .query((rs, row) -> new RunMeta(
                        rs.getTimestamp("completed_at") != null
                                ? rs.getTimestamp("completed_at").toInstant() : null,
                        rs.getString("commit_sha")
                ))
                .optional();
    }

    public record RunMeta(Instant indexedAt, String commitSha) {}
}

package io.testseer.backend.query.maven;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Resolves Maven module_path from hints (path, artifactId) — UP-GAP-07. */
@Service
public class MavenModuleLookupService {

    private final JdbcClient db;

    public MavenModuleLookupService(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param hint module path, artifactId, or blank for default root
     * @return resolved module_path (may be empty string for repo root)
     */
    public Optional<String> resolveModulePath(String serviceId, String commitSha, String hint) {
        if (hint == null || hint.isBlank()) {
            return resolveDefaultRoot(serviceId, commitSha);
        }
        if (moduleExists(serviceId, commitSha, hint)) {
            return Optional.of(hint);
        }
        Optional<String> byArtifact = lookupByArtifactId(serviceId, commitSha, hint);
        if (byArtifact.isPresent()) {
            return byArtifact;
        }
        List<String> modules = listModulePaths(serviceId, commitSha);
        if (modules.size() == 1) {
            return Optional.of(modules.get(0));
        }
        return Optional.empty();
    }

    public Optional<String> resolveDefaultRoot(String serviceId, String commitSha) {
        return db.sql("""
                SELECT module_path FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                  AND module_path IS NOT NULL AND trim(module_path) <> ''
                ORDER BY CASE packaging WHEN 'jar' THEN 0 WHEN 'war' THEN 1 ELSE 2 END,
                         length(module_path) DESC, module_path
                LIMIT 1
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .query(String.class)
                .optional()
                .or(() -> db.sql("""
                        SELECT module_path FROM maven_module_facts
                        WHERE service_id = :serviceId AND commit_sha = :commitSha
                        ORDER BY CASE WHEN module_path = '' OR module_path IS NULL THEN 0 ELSE 1 END,
                                 length(coalesce(module_path, ''))
                        LIMIT 1
                        """)
                        .param("serviceId", serviceId)
                        .param("commitSha", commitSha)
                        .query(String.class)
                        .optional()
                        .map(p -> p != null ? p : ""));
    }

    private boolean moduleExists(String serviceId, String commitSha, String modulePath) {
        Integer count = db.sql("""
                SELECT count(*) FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                  AND module_path = :modulePath
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .param("modulePath", modulePath)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private Optional<String> lookupByArtifactId(String serviceId, String commitSha, String artifactId) {
        return db.sql("""
                SELECT module_path FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                  AND lower(artifact_id) = lower(:artifactId)
                ORDER BY CASE packaging WHEN 'jar' THEN 0 WHEN 'war' THEN 1 ELSE 2 END
                LIMIT 1
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .param("artifactId", artifactId)
                .query(String.class)
                .optional()
                .map(p -> p != null ? p : "");
    }

    private List<String> listModulePaths(String serviceId, String commitSha) {
        return db.sql("""
                SELECT coalesce(module_path, '') FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                ORDER BY module_path
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .query(String.class)
                .list();
    }
}

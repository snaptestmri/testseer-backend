package io.testseer.backend.query.maven;

import io.testseer.backend.ingestion.FactBatch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MavenDependencyQueryService {

    private final JdbcClient db;

    public MavenDependencyQueryService(JdbcClient db) {
        this.db = db;
    }

    public MavenDependenciesReport query(
            String serviceId,
            String commitSha,
            String modulePath,
            String scope,
            boolean directOnly,
            String groupIdFilter,
            String artifactIdFilter) {

        List<ModuleView> modules = loadModules(serviceId, commitSha, modulePath);
        List<DependencyView> dependencies = loadDependencies(
                serviceId, commitSha, modulePath, scope, directOnly, groupIdFilter, artifactIdFilter);
        return new MavenDependenciesReport(modules, dependencies);
    }

    public String latestCommitSha(String serviceId) {
        return db.sql("""
                SELECT commit_sha FROM maven_module_facts
                WHERE service_id = :serviceId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("serviceId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private List<ModuleView> loadModules(String serviceId, String commitSha, String modulePath) {
        var spec = db.sql("""
                SELECT module_path, group_id, artifact_id, version, packaging, resolution_status, relative_pom_path
                FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha);
        if (modulePath != null && !modulePath.isBlank()) {
            spec = db.sql("""
                    SELECT module_path, group_id, artifact_id, version, packaging, resolution_status, relative_pom_path
                    FROM maven_module_facts
                    WHERE service_id = :serviceId AND commit_sha = :commitSha AND module_path = :modulePath
                    """)
                    .param("serviceId", serviceId)
                    .param("commitSha", commitSha)
                    .param("modulePath", modulePath);
        }
        return spec.query((rs, row) -> new ModuleView(
                rs.getString("module_path"),
                rs.getString("group_id"),
                rs.getString("artifact_id"),
                rs.getString("version"),
                rs.getString("packaging"),
                rs.getString("resolution_status"),
                rs.getString("relative_pom_path")
        )).list();
    }

    private List<DependencyView> loadDependencies(
            String serviceId,
            String commitSha,
            String modulePath,
            String scope,
            boolean directOnly,
            String groupIdFilter,
            String artifactIdFilter) {

        StringBuilder sql = new StringBuilder("""
                SELECT from_module_path, to_group_id, to_artifact_id, to_version, version_literal,
                       scope, optional, transitive, resolved, unresolved_reason,
                       linked_service_id, linked_repo, link_source, cross_repo
                FROM maven_dependency_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                """);
        if (modulePath != null && !modulePath.isBlank()) {
            sql.append(" AND from_module_path = :modulePath");
        }
        List<String> scopeFilter = MavenScopeFilter.sqlScopes(scope);
        if (!scopeFilter.isEmpty()) {
            sql.append(" AND scope IN (:scopes)");
        }
        if (directOnly) {
            sql.append(" AND transitive = false");
        }
        if (groupIdFilter != null && !groupIdFilter.isBlank()) {
            sql.append(" AND to_group_id = :groupId");
        }
        if (artifactIdFilter != null && !artifactIdFilter.isBlank()) {
            sql.append(" AND to_artifact_id = :artifactId");
        }
        sql.append(" ORDER BY from_module_path, to_group_id, to_artifact_id");

        var spec = db.sql(sql.toString())
                .param("serviceId", serviceId)
                .param("commitSha", commitSha);
        if (modulePath != null && !modulePath.isBlank()) {
            spec = spec.param("modulePath", modulePath);
        }
        if (!scopeFilter.isEmpty()) {
            spec = spec.param("scopes", scopeFilter);
        }
        if (groupIdFilter != null && !groupIdFilter.isBlank()) {
            spec = spec.param("groupId", groupIdFilter);
        }
        if (artifactIdFilter != null && !artifactIdFilter.isBlank()) {
            spec = spec.param("artifactId", artifactIdFilter);
        }
        return spec.query((rs, row) -> new DependencyView(
                rs.getString("from_module_path"),
                rs.getString("to_group_id"),
                rs.getString("to_artifact_id"),
                rs.getString("to_version"),
                rs.getString("version_literal"),
                rs.getString("scope"),
                rs.getBoolean("optional"),
                rs.getBoolean("transitive"),
                rs.getBoolean("resolved"),
                rs.getString("unresolved_reason"),
                rs.getString("linked_service_id"),
                rs.getString("linked_repo"),
                rs.getString("link_source"),
                rs.getBoolean("cross_repo")
        )).list();
    }

    public record MavenDependenciesReport(List<ModuleView> modules, List<DependencyView> dependencies) {}

    public record ModuleView(
            String modulePath,
            String groupId,
            String artifactId,
            String version,
            String packaging,
            String resolutionStatus,
            String relativePomPath
    ) {}

    public record DependencyView(
            String fromModulePath,
            String groupId,
            String artifactId,
            String version,
            String versionLiteral,
            String scope,
            boolean optional,
            boolean transitive,
            boolean resolved,
            String unresolvedReason,
            String linkedServiceId,
            String linkedRepo,
            String linkSource,
            boolean crossRepo
    ) {}
}

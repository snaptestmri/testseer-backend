package io.testseer.backend.query.maven;

import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.graph.GraphSubgraphHydrator;
import io.testseer.backend.graph.ReachabilityResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

@Service
public class DependencyTreeGraphService {

    private static final List<String> MAVEN_EDGE_TYPES = List.of("CONTAINS_MODULE", "DEPENDS_ON_ARTIFACT");

    private final JdbcClient db;
    private final MavenDependencyQueryService mavenQueryService;
    private final GraphSubgraphHydrator hydrator;

    public DependencyTreeGraphService(
            JdbcClient db,
            MavenDependencyQueryService mavenQueryService,
            GraphSubgraphHydrator hydrator) {
        this.db = db;
        this.mavenQueryService = mavenQueryService;
        this.hydrator = hydrator;
    }

    public DependencyTreeResult buildTree(
            String serviceId,
            String commitSha,
            String modulePath,
            String scope,
            int depth,
            boolean hydrate,
            boolean includeExternal) {

        int cappedDepth = Math.max(1, Math.min(depth, 4));

        String rootModule = resolveRootModule(serviceId, commitSha, modulePath);
        if (rootModule == null) {
            return new DependencyTreeResult(null, List.of(), List.of(), List.of(), 0);
        }

        Set<String> reachableModules = new LinkedHashSet<>();
        Set<String> artifactNodeIds = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(rootModule);
        reachableModules.add(rootModule);

        int hops = 0;
        while (!queue.isEmpty() && hops < cappedDepth) {
            int levelSize = queue.size();
            hops++;
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                expandModule(serviceId, commitSha, current, scope, includeExternal, reachableModules, artifactNodeIds, queue);
            }
        }

        List<String> nodeIds = new ArrayList<>();
        for (String mod : reachableModules) {
            nodeIds.add(GraphNodeIds.mavenModuleNode(serviceId, mod));
        }
        nodeIds.addAll(artifactNodeIds);

        int unresolved = countUnresolved(serviceId, commitSha, reachableModules, scope);

        if (!hydrate) {
            return new DependencyTreeResult(rootModule, nodeIds, List.of(), List.of(), unresolved);
        }

        String anchor = GraphNodeIds.mavenModuleNode(serviceId, rootModule);
        ReachabilityResult hydrated = hydrator.hydrate(anchor, nodeIds, MAVEN_EDGE_TYPES);
        return new DependencyTreeResult(
                rootModule, hydrated.nodeIds(), hydrated.nodes(), hydrated.edges(), unresolved);
    }

    private void expandModule(
            String serviceId,
            String commitSha,
            String modulePath,
            String scope,
            boolean includeExternal,
            Set<String> reachableModules,
            Set<String> artifactNodeIds,
            Queue<String> queue) {

        List<MavenDependencyQueryService.DependencyView> deps = mavenQueryService.query(
                serviceId, commitSha, modulePath, scope, false, null, null).dependencies();

        for (MavenDependencyQueryService.DependencyView dep : deps) {
            if (!includeExternal && dep.groupId() != null && !dep.groupId().startsWith("com.quotient")) {
                continue;
            }
            String version = dep.version() != null ? dep.version() : dep.versionLiteral();
            artifactNodeIds.add(GraphNodeIds.artifactNode(dep.groupId(), dep.artifactId(), version));
        }

        List<String> childModules = db.sql("""
                SELECT child.module_path
                FROM maven_module_facts parent
                JOIN maven_module_facts child
                  ON parent.service_id = child.service_id
                 AND parent.commit_sha = child.commit_sha
                 AND child.module_path LIKE parent.module_path || '/%'
                WHERE parent.service_id = :serviceId
                  AND parent.commit_sha = :commitSha
                  AND parent.module_path = :modulePath
                  AND position('/' in substring(child.module_path from length(parent.module_path) + 2)) = 0
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .param("modulePath", modulePath)
                .query(String.class)
                .list();

        for (String child : childModules) {
            if (reachableModules.add(child)) {
                queue.add(child);
            }
        }
    }

    private String resolveRootModule(String serviceId, String commitSha, String modulePath) {
        if (modulePath != null && !modulePath.isBlank()) {
            return modulePath;
        }
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
                .orElse(null);
    }

    private int countUnresolved(String serviceId, String commitSha, Set<String> modules, String scope) {
        if (modules.isEmpty()) {
            return 0;
        }
        var spec = db.sql("""
                SELECT count(*) FROM maven_dependency_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                  AND from_module_path IN (:modules)
                  AND resolved = false
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .param("modules", modules);
        List<String> scopeFilter = MavenScopeFilter.sqlScopes(scope);
        if (!scopeFilter.isEmpty()) {
            spec = db.sql("""
                    SELECT count(*) FROM maven_dependency_facts
                    WHERE service_id = :serviceId AND commit_sha = :commitSha
                      AND from_module_path IN (:modules)
                      AND resolved = false AND scope IN (:scopes)
                    """)
                    .param("serviceId", serviceId)
                    .param("commitSha", commitSha)
                    .param("modules", modules)
                    .param("scopes", scopeFilter);
        }
        Integer count = spec.query(Integer.class).single();
        return count != null ? count : 0;
    }

    public record DependencyTreeResult(
            String rootModulePath,
            List<String> nodeIds,
            List<?> nodes,
            List<?> edges,
            int unresolvedCount
    ) {}
}

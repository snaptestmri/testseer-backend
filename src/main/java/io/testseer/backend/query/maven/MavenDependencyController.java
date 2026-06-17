package io.testseer.backend.query.maven;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.query.CacheService;
import io.testseer.backend.query.FreshnessResolver;
import io.testseer.backend.query.FreshnessStatus;
import io.testseer.backend.query.ResponseEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Query — Maven dependencies", description = "Maven module and artifact dependency facts (BL-058)")
@RestController
@RequestMapping("/v1")
public class MavenDependencyController {

    private final MavenDependencyQueryService mavenQueryService;
    private final DependencyTreeGraphService treeService;
    private final FreshnessResolver freshnessResolver;
    private final CacheService cache;
    private final int staleThresholdMinutes;

    public MavenDependencyController(
            MavenDependencyQueryService mavenQueryService,
            DependencyTreeGraphService treeService,
            FreshnessResolver freshnessResolver,
            CacheService cache,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.mavenQueryService = mavenQueryService;
        this.treeService = treeService;
        this.freshnessResolver = freshnessResolver;
        this.cache = cache;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Maven dependency facts for a service")
    @GetMapping("/facts/maven-dependencies")
    public ResponseEntity<ResponseEnvelope<MavenDependencyQueryService.MavenDependenciesReport>> mavenDependencies(
            @RequestParam String serviceId,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo,
            @RequestParam(required = false) String modulePath,
            @RequestParam(defaultValue = "runtime") String scope,
            @RequestParam(defaultValue = "false") boolean directOnly,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String artifactId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        String commitSha = mavenQueryService.latestCommitSha(serviceId);
        if (commitSha == null) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        String cacheKey = modulePath + "|" + scope + "|" + directOnly + "|" + groupId + "|" + artifactId;
        MavenDependencyQueryService.MavenDependenciesReport report = cache.get(
                orgId, repo, serviceId, "facts:maven-deps", cacheKey,
                () -> mavenQueryService.query(serviceId, commitSha, modulePath, scope, directOnly, groupId, artifactId),
                MavenDependencyQueryService.MavenDependenciesReport.class);

        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus)
                .body(ResponseEnvelope.of(null, commitSha, status, report));
    }

    @Operation(summary = "Maven dependency tree with optional graph hydration")
    @GetMapping("/graph/dependency-tree")
    public ResponseEntity<ResponseEnvelope<DependencyTreeGraphService.DependencyTreeResult>> dependencyTree(
            @RequestParam String serviceId,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo,
            @RequestParam(required = false) String modulePath,
            @RequestParam(defaultValue = "runtime") String scope,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(defaultValue = "true") boolean hydrate,
            @RequestParam(defaultValue = "true") boolean includeExternal) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        String commitSha = mavenQueryService.latestCommitSha(serviceId);
        if (commitSha == null) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        final String sha = commitSha;
        String cacheKey = modulePath + "|" + scope + "|" + depth + "|" + hydrate + "|" + includeExternal;
        DependencyTreeGraphService.DependencyTreeResult result = cache.get(
                orgId, repo, serviceId, "graph:dependency-tree", cacheKey,
                () -> treeService.buildTree(serviceId, sha, modulePath, scope, depth, hydrate, includeExternal),
                DependencyTreeGraphService.DependencyTreeResult.class);

        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus)
                .body(ResponseEnvelope.of(null, commitSha, status, result));
    }
}

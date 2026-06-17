package io.testseer.backend.ingestion.maven;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Caches a full-repo Maven fact build (all modules + resolved trees) per org/repo/commit checkout.
 * Catalog-library profiles slice from this snapshot instead of re-running Maven for every profile.
 */
@Component
public class MavenRepoFactsCache {

    private static final Logger log = LoggerFactory.getLogger(MavenRepoFactsCache.class);

    private final ConcurrentMap<CacheKey, MavenRepoFactsBuild> cache = new ConcurrentHashMap<>();

    public MavenRepoFactsBuild getOrBuild(CacheKey key, Supplier<MavenRepoFactsBuild> builder) {
        MavenRepoFactsBuild existing = cache.get(key);
        if (existing != null) {
            log.info("Maven repo facts cache hit for {}/{}@{}", key.orgId(), key.repo(), abbrev(key.commitSha()));
            return existing;
        }
        return cache.computeIfAbsent(key, k -> {
            log.info("Maven repo facts cache miss for {}/{}@{} — building full repo snapshot",
                    k.orgId(), k.repo(), abbrev(k.commitSha()));
            return builder.get();
        });
    }

    public void clear() {
        cache.clear();
    }

    private static String abbrev(String sha) {
        if (sha == null || sha.length() <= 8) {
            return sha;
        }
        return sha.substring(0, 8);
    }

    public record CacheKey(String orgId, String repo, String commitSha, String normalizedRepoPath, boolean treeResolution) {
        public static CacheKey of(String orgId, String repo, String commitSha, String repoLocalPath, boolean treeResolution) {
            String normalized = Path.of(repoLocalPath).normalize().toString();
            return new CacheKey(orgId, repo, commitSha != null ? commitSha : "", normalized, treeResolution);
        }
    }

    public record MavenRepoFactsBuild(
            List<PomStructureExtractor.ParsedPom> parsedPoms,
            List<ScopedModule> modules,
            List<ScopedDependency> dependencies,
            List<String> containsModuleEdges
    ) {}

    public record ScopedModule(PomStructureExtractor.ParsedPom pom, String resolutionStatus) {}

    public record ScopedDependency(
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
            String evidenceSource
    ) {}
}

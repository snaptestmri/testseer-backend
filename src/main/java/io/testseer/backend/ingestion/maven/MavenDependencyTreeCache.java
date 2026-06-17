package io.testseer.backend.ingestion.maven;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;

/**
 * In-memory cache for {@code mvn dependency:tree} output keyed by repo path, commit, and module POM.
 * Shared across catalog-library profiles that index the same monorepo checkout in one backend session.
 */
@Component
public class MavenDependencyTreeCache {

    private static final Logger log = LoggerFactory.getLogger(MavenDependencyTreeCache.class);

    private final ConcurrentMap<String, List<MavenDependencyTreeResolver.ResolvedDependency>> cache =
            new ConcurrentHashMap<>();

    public List<MavenDependencyTreeResolver.ResolvedDependency> getOrResolve(
            String repoLocalPath,
            String commitSha,
            String relativePomPath,
            IntSupplier timeoutSeconds,
            MavenDependencyTreeResolver resolver) {
        String key = cacheKey(repoLocalPath, commitSha, relativePomPath);
        List<MavenDependencyTreeResolver.ResolvedDependency> cached = cache.get(key);
        if (cached != null) {
            log.debug("dependency:tree cache hit for {}", relativePomPath);
            return cached;
        }
        return cache.computeIfAbsent(key, k -> {
            List<MavenDependencyTreeResolver.ResolvedDependency> resolved = List.copyOf(
                    resolver.resolveModuleUncached(repoLocalPath, relativePomPath, timeoutSeconds.getAsInt()));
            log.debug("dependency:tree cache miss for {} ({} deps)", relativePomPath, resolved.size());
            return resolved;
        });
    }

    public void clear() {
        cache.clear();
    }

    static String cacheKey(String repoLocalPath, String commitSha, String relativePomPath) {
        String normalizedRepo = Path.of(repoLocalPath).normalize().toString();
        String sha = commitSha != null ? commitSha : "";
        String pom = relativePomPath != null ? relativePomPath : "pom.xml";
        return normalizedRepo + "|" + sha + "|" + pom;
    }
}

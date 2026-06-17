package io.testseer.backend.ingestion.maven;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MavenDependencyTreeCacheTest {

    @Test
    void getOrResolve_returnsCachedResultForSameKey() {
        MavenDependencyTreeCache cache = new MavenDependencyTreeCache();
        AtomicInteger calls = new AtomicInteger();

        MavenDependencyTreeResolver resolver = new MavenDependencyTreeResolver(cache) {
            @Override
            List<ResolvedDependency> resolveModuleUncached(
                    String repoLocalPath, String relativePomPath, int timeoutSeconds) {
                calls.incrementAndGet();
                return List.of(new ResolvedDependency("g", "a", "1", "compile", false));
            }
        };

        List<MavenDependencyTreeResolver.ResolvedDependency> first = resolver.resolveModule(
                "/repo", "sha1", "pom.xml", 30);
        List<MavenDependencyTreeResolver.ResolvedDependency> second = resolver.resolveModule(
                "/repo", "sha1", "pom.xml", 30);

        assertThat(first).hasSize(1);
        assertThat(second).isEqualTo(first);
        assertThat(calls).hasValue(1);
    }
}

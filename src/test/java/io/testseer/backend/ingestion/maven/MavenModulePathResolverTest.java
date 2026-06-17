package io.testseer.backend.ingestion.maven;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenModulePathResolverTest {

    @Test
    void modulePathFromSourceRoot_stripsJavaSrc() {
        assertThat(MavenModulePathResolver.modulePathFromSourceRoot("platform-data/src/main/java"))
                .isEqualTo("platform-data");
        assertThat(MavenModulePathResolver.modulePathFromSourceRoot("src/main/java")).isEmpty();
    }

    @Test
    void pomRootsFromProfile_dedupesModulePaths() {
        List<String> roots = MavenModulePathResolver.pomRootsFromProfile(
                List.of("platform-data/src/main/java", "platform-api/src/main/java"),
                List.of());
        assertThat(roots).containsExactly("platform-data", "platform-api");
    }

    @Test
    void moduleInScope_includesRootAndDescendants() {
        List<String> pomRoots = List.of("platform-data");
        assertThat(MavenModulePathResolver.moduleInScope("", pomRoots)).isTrue();
        assertThat(MavenModulePathResolver.moduleInScope("platform-data", pomRoots)).isTrue();
        assertThat(MavenModulePathResolver.moduleInScope("platform-data/sub", pomRoots)).isTrue();
        assertThat(MavenModulePathResolver.moduleInScope("platform-api", pomRoots)).isFalse();
    }

    @Test
    void moduleInScope_emptyRootsMeansWholeRepo() {
        assertThat(MavenModulePathResolver.moduleInScope("anything", List.of())).isTrue();
    }
}

package io.testseer.backend.ingestion.maven;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenDependencyTreeResolverTest {

  @Test
  void parsesTransitiveEdges() {
    String tree =
        """
        com.quotient:transaction-eval-consumer:jar:1.0-SNAPSHOT
        +- com.quotient:platform-evaluation-lib:jar:2.14.0:compile
        |  \\- org.springframework:spring-core:jar:5.3.30:compile
        \\- com.google.guava:guava:jar:32.1.3-jre:compile
        """;

    List<MavenDependencyTreeResolver.ResolvedDependency> deps =
        MavenDependencyTreeResolver.parseTreeText(tree);

    assertThat(deps).extracting(MavenDependencyTreeResolver.ResolvedDependency::artifactId)
        .contains("platform-evaluation-lib", "spring-core", "guava");
    assertThat(deps.stream().filter(MavenDependencyTreeResolver.ResolvedDependency::transitive))
        .isNotEmpty();
  }
}

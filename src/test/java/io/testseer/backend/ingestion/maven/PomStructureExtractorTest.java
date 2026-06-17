package io.testseer.backend.ingestion.maven;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PomStructureExtractorTest {

  @Test
  void extractsParentAndModules() {
    String parentPom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>parent</artifactId>
          <version>1.0.0-SNAPSHOT</version>
          <packaging>pom</packaging>
          <modules>
            <module>child-a</module>
            <module>child-b</module>
          </modules>
        </project>
        """;

    List<PomStructureExtractor.ParsedPom> parsed =
        PomStructureExtractor.extractAll(
            List.of(new PomStructureExtractor.PomInput("pom.xml", parentPom)));

    assertThat(parsed).hasSize(1);
    assertThat(parsed.get(0).childModules()).containsExactly("child-a", "child-b");
    assertThat(parsed.get(0).rootModule()).isTrue();
  }

  @Test
  void extractsDeclaredDependencies() {
    String pom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>transaction-eval-consumer</artifactId>
          <version>1.0.0-SNAPSHOT</version>
          <dependencies>
            <dependency>
              <groupId>com.quotient</groupId>
              <artifactId>platform-evaluation-lib</artifactId>
              <version>2.14.0</version>
            </dependency>
          </dependencies>
        </project>
        """;

    List<PomStructureExtractor.ParsedPom> parsed =
        PomStructureExtractor.extractAll(
            List.of(
                new PomStructureExtractor.PomInput(
                    "evaluation-consumers/transaction-eval-consumer/pom.xml", pom)));

    assertThat(parsed.get(0).dependencies()).hasSize(1);
    assertThat(parsed.get(0).dependencies().get(0).artifactId())
        .isEqualTo("platform-evaluation-lib");
  }

  @Test
  void flagsUnresolvedPropertyVersion() {
    String pom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>demo</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.quotient</groupId>
              <artifactId>platform-evaluation-lib</artifactId>
              <version>${platform.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    var dep =
        PomStructureExtractor.extractAll(
                List.of(new PomStructureExtractor.PomInput("pom.xml", pom)))
            .get(0)
            .dependencies()
            .get(0);

    assertThat(PomStructureExtractor.isUnresolvedVersion(dep.versionLiteral())).isTrue();
    assertThat(PomStructureExtractor.unresolvedReason(dep.versionLiteral())).isEqualTo("PROPERTY");
  }
}

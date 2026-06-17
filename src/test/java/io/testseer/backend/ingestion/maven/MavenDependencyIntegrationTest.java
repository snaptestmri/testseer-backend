package io.testseer.backend.ingestion.maven;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import io.testseer.backend.graph.MavenGraphProjector;
import io.testseer.backend.ingestion.DualWriteService;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "testseer.maven.tree-resolution-enabled=false")
class MavenDependencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired DualWriteService dualWriteService;
  @Autowired MavenFactOrchestrator mavenFactOrchestrator;
  @Autowired MavenGraphProjector mavenGraphProjector;
  @Autowired ServiceRegistryService svcRegistry;
  @Autowired io.testseer.backend.ingestion.maven.MavenLinkBackfillService backfillService;
  @Autowired JdbcClient db;

  String serviceId;
  String libServiceId;
  String commitSha = "maven-test-sha";

  @BeforeEach
  void setup() {
    IntegrationTestDb.clearCoreFacts(db);

    libServiceId =
        svcRegistry
            .register(
                new RegistrationRequest(
                    "quotient",
                    "platform-evaluation-lib",
                    "evaluation-lib",
                    "MAVEN",
                    "library",
                    List.of("evaluation-lib/src/main/java"),
                    List.of(),
                    null))
            .serviceId();

    var reg =
        svcRegistry.register(
            new RegistrationRequest(
                "quotient",
                "demo-service",
                "demo-service",
                "MAVEN",
                "service",
                List.of("src/main/java"),
                List.of("src/test/java"),
                null));
    serviceId = reg.serviceId();
  }

  @Test
  void index_writesModuleAndDirectDeps() {
    String pom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>demo-service</artifactId>
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

    var facts =
        mavenFactOrchestrator.build(
            "quotient",
            "demo-service",
            serviceId,
            commitSha,
            "MAVEN",
            List.of(new GitHubSourceFetcher.FetchedFile("pom.xml", pom)),
            null);

    FactBatch batch =
        FactBatch.core("job-mvn", "quotient", "demo-service", serviceId, commitSha, "BASELINE", List.of(), List.of(), List.of(), List.of())
            .withMavenFacts(facts.modules(), facts.dependencies());

    dualWriteService.write(batch, List.of());
    mavenGraphProjector.project(batch, facts);

    Integer moduleCount =
        db.sql("SELECT count(*) FROM maven_module_facts WHERE service_id = :svc")
            .param("svc", serviceId)
            .query(Integer.class)
            .single();
    Integer depCount =
        db.sql(
                "SELECT count(*) FROM maven_dependency_facts WHERE service_id = :svc AND to_artifact_id = 'platform-evaluation-lib'")
            .param("svc", serviceId)
            .query(Integer.class)
            .single();

    assertThat(moduleCount).isGreaterThanOrEqualTo(1);
    assertThat(depCount).isGreaterThanOrEqualTo(1);

    Integer edges =
        db.sql(
                "SELECT count(*) FROM graph_edges WHERE edge_type = 'DEPENDS_ON_ARTIFACT' AND from_node LIKE :prefix")
            .param("prefix", serviceId + "::maven::%")
            .query(Integer.class)
            .single();
    assertThat(edges).isGreaterThanOrEqualTo(1);
  }

  @Test
  void index_projectsOwnedByEdgeForCrossRepoLink() {
    String pom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>demo-service</artifactId>
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

    var facts =
        mavenFactOrchestrator.build(
            "quotient",
            "demo-service",
            serviceId,
            commitSha,
            "MAVEN",
            List.of(new GitHubSourceFetcher.FetchedFile("pom.xml", pom)),
            null);

    FactBatch batch =
        FactBatch.core("job-mvn", "quotient", "demo-service", serviceId, commitSha, "BASELINE", List.of(), List.of(), List.of(), List.of())
            .withMavenFacts(facts.modules(), facts.dependencies());

    dualWriteService.write(batch, List.of());
    mavenGraphProjector.project(batch, facts);

    Integer ownedBy =
        db.sql("""
                SELECT count(*) FROM graph_edges
                WHERE edge_type = 'OWNED_BY' AND to_node = :owner
                """)
            .param("owner", libServiceId)
            .query(Integer.class)
            .single();

    assertThat(ownedBy).isGreaterThanOrEqualTo(1);
  }

  @Test
  void backfill_updatesLinksWithoutReindex() {
    String pom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>demo-service</artifactId>
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

    var facts =
        mavenFactOrchestrator.build(
            "quotient",
            "demo-service",
            serviceId,
            commitSha,
            "MAVEN",
            List.of(new GitHubSourceFetcher.FetchedFile("pom.xml", pom)),
            null);

    FactBatch batch =
        FactBatch.core("job-mvn", "quotient", "demo-service", serviceId, commitSha, "BASELINE", List.of(), List.of(), List.of(), List.of())
            .withMavenFacts(
                facts.modules(),
                facts.dependencies().stream()
                    .map(d -> new FactBatch.MavenDependencyFact(
                            d.orgId(), d.repo(), d.serviceId(), d.commitSha(),
                            d.fromModulePath(), d.toGroupId(), d.toArtifactId(),
                            d.toVersion(), d.versionLiteral(), d.scope(), d.optional(),
                            d.transitive(), d.resolved(), d.unresolvedReason(),
                            null, null, null, false, d.evidenceSource(), d.confidence()))
                    .toList());

    dualWriteService.write(batch, List.of());

    var response =
        backfillService.backfill(new io.testseer.backend.admin.MavenLinkBackfillRequest(
                "quotient", serviceId, true));

    assertThat(response.dependencyRowsUpdated()).isGreaterThanOrEqualTo(1);

    String linked =
        db.sql("""
                SELECT linked_service_id FROM maven_dependency_facts
                WHERE service_id = :svc AND to_artifact_id = 'platform-evaluation-lib'
                LIMIT 1
                """)
            .param("svc", serviceId)
            .query(String.class)
            .single();

    assertThat(linked).isEqualTo(libServiceId);
  }

  @Test
  void index_setsLinkedServiceIdForInternalArtifact() {
    String pom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>demo-service</artifactId>
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

    var facts =
        mavenFactOrchestrator.build(
            "quotient",
            "demo-service",
            serviceId,
            commitSha,
            "MAVEN",
            List.of(new GitHubSourceFetcher.FetchedFile("pom.xml", pom)),
            null);

    FactBatch batch =
        FactBatch.core("job-mvn", "quotient", "demo-service", serviceId, commitSha, "BASELINE", List.of(), List.of(), List.of(), List.of())
            .withMavenFacts(facts.modules(), facts.dependencies());

    dualWriteService.write(batch, List.of());

    var row =
        db.sql("""
                SELECT linked_service_id, linked_repo, link_source, cross_repo
                FROM maven_dependency_facts
                WHERE service_id = :svc AND to_artifact_id = 'platform-evaluation-lib'
                LIMIT 1
                """)
            .param("svc", serviceId)
            .query((rs, n) -> {
                String linkedServiceId = rs.getString("linked_service_id");
                String linkedRepo = rs.getString("linked_repo");
                String linkSource = rs.getString("link_source");
                boolean crossRepo = rs.getBoolean("cross_repo");
                return new Object[] { linkedServiceId, linkedRepo, linkSource, crossRepo };
            })
            .single();

    assertThat(row[0]).isEqualTo(libServiceId);
    assertThat(row[1]).isEqualTo("platform-evaluation-lib");
    assertThat(row[2]).isNotNull();
    assertThat(row[3]).isEqualTo(true);
  }

  @Test
  void index_skipsLinkForSameRepoModuleDependency() {
    String parentPom =
        """
        <project>
          <groupId>com.quotient</groupId>
          <artifactId>demo-service</artifactId>
          <version>1.0.0-SNAPSHOT</version>
          <packaging>pom</packaging>
          <modules><module>child</module></modules>
        </project>
        """;
    String childPom =
        """
        <project>
          <parent>
            <groupId>com.quotient</groupId>
            <artifactId>demo-service</artifactId>
            <version>1.0.0-SNAPSHOT</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.quotient</groupId>
              <artifactId>demo-service</artifactId>
              <version>1.0.0-SNAPSHOT</version>
            </dependency>
          </dependencies>
        </project>
        """;

    var facts =
        mavenFactOrchestrator.build(
            "quotient",
            "demo-service",
            serviceId,
            commitSha,
            "MAVEN",
            List.of(
                new GitHubSourceFetcher.FetchedFile("pom.xml", parentPom),
                new GitHubSourceFetcher.FetchedFile("child/pom.xml", childPom)),
            null);

    assertThat(facts.dependencies()).isNotEmpty();
    assertThat(facts.dependencies().stream().filter(d -> "demo-service".equals(d.toArtifactId())))
        .allMatch(d -> d.linkedServiceId() == null);
  }
}

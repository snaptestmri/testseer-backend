package io.testseer.backend.ingestion.maven;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InternalArtifactLinkerTest extends AbstractIntegrationTest {

    @Autowired InternalArtifactLinker linker;
    @Autowired ServiceRegistryService svcRegistry;
    @Autowired JdbcClient db;

    String orgId = "quotient";
    String consumerServiceId;
    String libServiceId;

    @BeforeEach
    void setup() {
        IntegrationTestDb.clearCoreFacts(db);

        consumerServiceId = svcRegistry.register(new RegistrationRequest(
                orgId, "demo-service", "demo-service", "MAVEN", "service",
                java.util.List.of("src/main/java"), java.util.List.of("src/test/java"), null)).serviceId();

        libServiceId = svcRegistry.register(new RegistrationRequest(
                orgId, "platform-evaluation-lib", "evaluation-lib", "MAVEN", "library",
                java.util.List.of("evaluation-lib/src/main/java"), java.util.List.of(), null)).serviceId();
    }

    @Test
    void linksByRegistryRepoName() {
        String registryOnlyLibId = svcRegistry.register(new RegistrationRequest(
                orgId, "acme-internal-lib", "acme-internal-lib", "MAVEN", "library",
                java.util.List.of("src/main/java"), java.util.List.of(), null)).serviceId();

        InternalArtifactLinker.LinkIndex index = linker.buildLinkIndex(orgId);

        var link = linker.resolve(orgId, consumerServiceId, "com.acme",
                "acme-internal-lib", index, Set.of());

        assertThat(link).isPresent();
        assertThat(link.get().serviceId()).isEqualTo(registryOnlyLibId);
        assertThat(link.get().source()).isEqualTo(InternalArtifactLinker.LinkSource.REGISTRY);
    }

    @Test
    void linksByCatalogLibraryId() {
        db.sql("""
                INSERT INTO workspace_catalog_library
                  (org_id, library_id, repo, service_name, source_roots, index_ddl)
                VALUES (:orgId, 'evaluation-lib', 'platform-evaluation-lib', 'evaluation-lib',
                        '{"evaluation-lib/src/main/java"}', false)
                ON CONFLICT (org_id, library_id) DO UPDATE SET
                  repo = EXCLUDED.repo, service_name = EXCLUDED.service_name
                """)
                .param("orgId", orgId)
                .update();

        InternalArtifactLinker.LinkIndex index = linker.buildLinkIndex(orgId);

        var link = linker.resolve(orgId, consumerServiceId, "com.quotient",
                "evaluation-lib", index, Set.of());

        assertThat(link).isPresent();
        assertThat(link.get().serviceId()).isEqualTo(libServiceId);
        assertThat(link.get().source()).isEqualTo(InternalArtifactLinker.LinkSource.CATALOG);
    }

    @Test
    void linksByMavenModuleGav() {
        db.sql("""
                INSERT INTO maven_module_facts
                  (org_id, repo, service_id, commit_sha, module_path, relative_pom_path,
                   group_id, artifact_id, version, packaging, is_root_module, resolution_status)
                VALUES (:orgId, 'platform-evaluation-lib', :libSvc, 'lib-sha', 'evaluation-lib',
                        'evaluation-lib/pom.xml', 'com.quotient', 'platform-evaluation-lib', '2.14.0',
                        'jar', false, 'RESOLVED')
                """)
                .param("orgId", orgId)
                .param("libSvc", libServiceId)
                .update();

        InternalArtifactLinker.LinkIndex index = linker.buildLinkIndex(orgId);

        var link = linker.resolve(orgId, consumerServiceId, "com.quotient",
                "platform-evaluation-lib", index, Set.of());

        assertThat(link).isPresent();
        assertThat(link.get().serviceId()).isEqualTo(libServiceId);
        assertThat(link.get().source()).isEqualTo(InternalArtifactLinker.LinkSource.MAVEN_MODULE_GAV);
    }

    @Test
    void linksByRulePackAlias() {
        InternalArtifactLinker.LinkIndex index = linker.buildLinkIndex(orgId);

        var link = linker.resolve(orgId, consumerServiceId, "com.quotient",
                "platform-evaluation-lib", index, Set.of());

        assertThat(link).isPresent();
        assertThat(link.get().serviceId()).isEqualTo(libServiceId);
    }

    @Test
    void skipsSameRepoModule() {
        InternalArtifactLinker.LinkIndex index = linker.buildLinkIndex(orgId);
        Set<String> local = Set.of(InternalArtifactLinker.gavKey("com.quotient", "platform-evaluation-lib"));

        var link = linker.resolve(orgId, consumerServiceId, "com.quotient",
                "platform-evaluation-lib", index, local);

        assertThat(link).isEmpty();
    }

    @Test
    void skipsSelfLink() {
        InternalArtifactLinker.LinkIndex index = linker.buildLinkIndex(orgId);

        var link = linker.resolve(orgId, libServiceId, "com.quotient",
                "platform-evaluation-lib", index, Set.of());

        assertThat(link).isEmpty();
    }
}

package io.testseer.backend.config;

import io.testseer.backend.config.workspace.OrgWorkspaceConfigResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceCatalogServiceTest {

    @Test
    void resolveIndexProfile_prefersExplicitCatalogLibraryIdOverRepoDefault() {
        WorkspaceConfig config = new WorkspaceConfig(
                "~/Documents/GitHub",
                "quotient",
                "quotient-full",
                List.of(),
                List.of(
                        new WorkspaceConfig.CatalogLibraryConfig(
                                "platform-data", "optimus-platform-framework", "platform-data",
                                List.of("platform-data/src/main/java"), false, false),
                        new WorkspaceConfig.CatalogLibraryConfig(
                                "platform-bigquery", "optimus-platform-framework", "platform-bigquery",
                                List.of("platform-bigquery/src/main/java"), false, false)
                ),
                null,
                null
        );
        OrgWorkspaceConfigResolver resolver = mock(OrgWorkspaceConfigResolver.class);
        when(resolver.resolve("quotient")).thenReturn(config);
        WorkspaceCatalogService service = new WorkspaceCatalogService(
                mock(WorkspaceConfigLoader.class), resolver);

        Optional<WorkspaceCatalogService.IndexProfile> bigquery = service.resolveIndexProfile(
                "quotient", "optimus-platform-framework", "platform-bigquery", null);

        assertThat(bigquery).isPresent();
        assertThat(bigquery.get().serviceId()).isEqualTo("platform-bigquery");
        assertThat(bigquery.get().sourceRoots()).containsExactly("platform-bigquery/src/main/java");

        Optional<WorkspaceCatalogService.IndexProfile> byRepo = service.resolveRepoProfile(
                "quotient", "optimus-platform-framework");

        assertThat(byRepo).isPresent();
        assertThat(byRepo.get().serviceId()).isEqualTo("platform-data");
    }

    @Test
    void findServiceModule_fallsBackToYamlWhenMissingFromMergedConfig() {
        WorkspaceConfig dbOnly = new WorkspaceConfig(
                "~/Documents/GitHub",
                "quotient",
                "quotient-full",
                List.of(),
                List.of(),
                List.of(new WorkspaceConfig.ServiceModuleConfig(
                        "partner-adapter-suite", "riq-partner-adapter-suite",
                        List.of("partner-adapter-lib/src/main/java"), List.of())),
                null
        );
        WorkspaceConfig yaml = new WorkspaceConfig(
                "~/Documents/GitHub",
                "quotient",
                "quotient-full",
                List.of(),
                null,
                List.of(new WorkspaceConfig.ServiceModuleConfig(
                        "optimus-platform-msg-framework", "optimus-platform-msg-framework",
                        List.of("platform-msg-consumer/src/main/java"), List.of())),
                null
        );
        WorkspaceConfigLoader yamlLoader = mock(WorkspaceConfigLoader.class);
        when(yamlLoader.getConfig()).thenReturn(yaml);
        OrgWorkspaceConfigResolver resolver = mock(OrgWorkspaceConfigResolver.class);
        when(resolver.resolve("quotient")).thenReturn(dbOnly);
        WorkspaceCatalogService service = new WorkspaceCatalogService(yamlLoader, resolver);

        Optional<WorkspaceConfig.ServiceModuleConfig> module =
                service.findServiceModule("quotient", "optimus-platform-msg-framework");

        assertThat(module).isPresent();
        assertThat(module.get().sourceRoots()).containsExactly("platform-msg-consumer/src/main/java");
    }
}

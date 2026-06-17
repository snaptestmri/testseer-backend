package io.testseer.backend.config.workspace;

import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.config.WorkspaceConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgWorkspaceConfigResolverTest {

    @Test
    void resolve_mergesYamlCatalogAndServiceModulesWhenDbIsPartial() {
        WorkspaceConfig yaml = new WorkspaceConfig(
                "~/Documents/GitHub",
                "quotient",
                "quotient-full",
                List.of(),
                List.of(
                        new WorkspaceConfig.CatalogLibraryConfig(
                                "platform-data", "optimus-platform-framework", "platform-data",
                                List.of("platform-data/src/main/java"), false, false),
                        new WorkspaceConfig.CatalogLibraryConfig(
                                "riq-platform-db", "riq-platform-db", "riq-platform-db",
                                List.of("Renew/MariaDB"), true, false)
                ),
                List.of(
                        new WorkspaceConfig.ServiceModuleConfig(
                                "partner-adapter-suite", "riq-partner-adapter-suite",
                                List.of("partner-adapter-lib/src/main/java"), List.of()),
                        new WorkspaceConfig.ServiceModuleConfig(
                                "optimus-platform-msg-framework", "optimus-platform-msg-framework",
                                List.of("platform-msg-consumer/src/main/java"), List.of())
                ),
                Map.of("quotient-full", new WorkspaceConfig.BundleConfig(List.of(), List.of(), null, List.of()))
        );

        OrgWorkspaceConfigRepository repository = mock(OrgWorkspaceConfigRepository.class);
        when(repository.findOrgSettings("quotient")).thenReturn(java.util.Optional.empty());
        when(repository.hasCatalogLibraries("quotient")).thenReturn(true);
        when(repository.listCatalogLibraries("quotient")).thenReturn(List.of(
                new WorkspaceConfig.CatalogLibraryConfig(
                        "platform-data", "optimus-platform-framework", "platform-data",
                        List.of("platform-data/src/main/java"), false, false)
        ));
        when(repository.hasServiceModules("quotient")).thenReturn(true);
        when(repository.listServiceModules("quotient")).thenReturn(List.of(
                new WorkspaceConfig.ServiceModuleConfig(
                        "partner-adapter-suite", "riq-partner-adapter-suite",
                        List.of("partner-adapter-lib/src/main/java"), List.of())
        ));
        when(repository.hasBundles("quotient")).thenReturn(false);

        WorkspaceConfigLoader loader = mock(WorkspaceConfigLoader.class);
        when(loader.getConfig()).thenReturn(yaml);

        OrgWorkspaceConfigResolver resolver = new OrgWorkspaceConfigResolver(loader, repository);
        WorkspaceConfig resolved = resolver.resolve("quotient");

        assertThat(resolved.catalogLibraries()).extracting(WorkspaceConfig.CatalogLibraryConfig::id)
                .containsExactly("platform-data", "riq-platform-db");
        assertThat(resolved.serviceModules()).extracting(WorkspaceConfig.ServiceModuleConfig::id)
                .containsExactly("partner-adapter-suite", "optimus-platform-msg-framework");
        assertThat(resolved.bundles()).containsKey("quotient-full");
    }
}

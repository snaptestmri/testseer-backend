package io.testseer.backend.webhook;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookCatalogRegistrarTest {

    @Mock WorkspaceCatalogService workspaceCatalog;
    @Mock ServiceRegistryService registryService;

    @InjectMocks WebhookCatalogRegistrar registrar;

    @Test
    void ensureCatalogRegistered_registersWhenMissing() {
        when(workspaceCatalog.findCatalogLibraryByRepo("quotient", "riq-platform-apis-optimus"))
                .thenReturn(Optional.of(new WorkspaceConfig.CatalogLibraryConfig(
                        "optimus-platform-apis",
                        "riq-platform-apis-optimus",
                        "riq-platform-apis-optimus",
                        List.of("reference", "Common/Models"),
                        false,
                        true)));
        when(registryService.getByOrgRepoAndName(
                "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus"))
                .thenReturn(Optional.empty());
        when(registryService.register(any())).thenReturn(new ServiceEntry(
                "new-id", "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                "library", "UNKNOWN", List.of("reference"), List.of(), null, true,
                Instant.now(), Instant.now()));

        registrar.ensureCatalogRegistered("quotient", "riq-platform-apis-optimus");

        ArgumentCaptor<RegistrationRequest> captor = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(registryService).register(captor.capture());
        assertThat(captor.getValue().moduleType()).isEqualTo("library");
        assertThat(captor.getValue().sourceRoots()).contains("reference");
    }

    @Test
    void ensureCatalogRegistered_skipsWhenAlreadyRegistered() {
        when(workspaceCatalog.findCatalogLibraryByRepo("quotient", "riq-platform-apis-optimus"))
                .thenReturn(Optional.of(new WorkspaceConfig.CatalogLibraryConfig(
                        "optimus-platform-apis",
                        "riq-platform-apis-optimus",
                        "riq-platform-apis-optimus",
                        List.of("reference"),
                        false,
                        true)));
        when(registryService.getByOrgRepoAndName(
                "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus"))
                .thenReturn(Optional.of(new ServiceEntry(
                        "existing", "quotient", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                        "library", "UNKNOWN", List.of("reference"), List.of(), null, true,
                        Instant.now(), Instant.now())));

        registrar.ensureCatalogRegistered("quotient", "riq-platform-apis-optimus");

        verify(registryService, never()).register(any());
    }
}

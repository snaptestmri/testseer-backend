package io.testseer.backend.admin;

import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    @Mock GitHubOrgScanner scanner;
    @Mock ServiceRegistryService registryService;

    @InjectMocks DiscoveryService service;

    @Test
    void discover_registersNewRepos() {
        when(scanner.scanJavaRepos("acme")).thenReturn(List.of(
                new GitHubOrgScanner.DetectedRepo("orders",   "MAVEN",  "main"),
                new GitHubOrgScanner.DetectedRepo("payments", "GRADLE", "main")
        ));
        when(registryService.register(any())).thenReturn(
                new ServiceEntry("svc-001", "acme", "orders", "orders", "service", "MAVEN",
                        List.of(), List.of(), null, true, null, null));

        DiscoveryResult result = service.discover("acme");

        assertThat(result.registered()).containsExactlyInAnyOrder("orders", "payments");
        assertThat(result.alreadyKnown()).isEmpty();
        verify(registryService, times(2)).register(any());
    }

    @Test
    void discover_placesAlreadyRegisteredInKnownList() {
        when(scanner.scanJavaRepos("acme")).thenReturn(List.of(
                new GitHubOrgScanner.DetectedRepo("orders", "MAVEN", "main")
        ));
        when(registryService.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "orders", "orders"));

        DiscoveryResult result = service.discover("acme");

        assertThat(result.registered()).isEmpty();
        assertThat(result.alreadyKnown()).containsExactly("orders");
    }

    @Test
    void discover_emptyOrg_returnsEmptyResult() {
        when(scanner.scanJavaRepos("acme")).thenReturn(List.of());

        DiscoveryResult result = service.discover("acme");

        assertThat(result.total()).isEqualTo(0);
        verifyNoInteractions(registryService);
    }
}

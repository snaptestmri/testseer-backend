package io.testseer.backend.webhook;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobDecomposerTest {

    @Mock
    ServiceRegistryService registryService;

    @InjectMocks
    JobDecomposer decomposer;

    private static ServiceEntry service(String id, String sourceRoot) {
        return new ServiceEntry(
                id, "acme", "monorepo", "svc-" + id,
                "service", "MAVEN",
                List.of(sourceRoot), List.of("src/test/java"),
                null, true, Instant.now(), Instant.now()
        );
    }

    @Test
    void decompose_mapsChangedFilesToCorrectService() {
        when(registryService.listAll()).thenReturn(List.of(
                service("orders", "services/orders/src/main/java"),
                service("inventory", "services/inventory/src/main/java")
        ));

        List<IngestionJob> jobs = decomposer.decompose(
                "acme", "monorepo", "abc123", "PR", 42,
                List.of(
                        "services/orders/src/main/java/OrderController.java",
                        "services/inventory/src/main/java/InventoryService.java"
                )
        );

        assertThat(jobs).hasSize(2);
        assertThat(jobs).extracting(IngestionJob::serviceId)
                .containsExactlyInAnyOrder("orders", "inventory");
    }

    @Test
    void decompose_ignoresFilesOutsideRegisteredRoots() {
        when(registryService.listAll()).thenReturn(List.of(
                service("orders", "services/orders/src/main/java")
        ));

        List<IngestionJob> jobs = decomposer.decompose(
                "acme", "monorepo", "abc123", "PR", 42,
                List.of("README.md", "infra/terraform/main.tf")
        );

        assertThat(jobs).isEmpty();
    }

    @Test
    void decompose_deduplicate_multipleFilesInSameService() {
        when(registryService.listAll()).thenReturn(List.of(
                service("orders", "services/orders/src/main/java")
        ));

        List<IngestionJob> jobs = decomposer.decompose(
                "acme", "monorepo", "abc123", "PR", 42,
                List.of(
                        "services/orders/src/main/java/A.java",
                        "services/orders/src/main/java/B.java"
                )
        );

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).changedFiles()).hasSize(2);
    }
}

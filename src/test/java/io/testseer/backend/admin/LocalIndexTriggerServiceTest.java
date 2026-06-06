package io.testseer.backend.admin;

import io.testseer.backend.graph.GraphFactProjector;
import io.testseer.backend.ingestion.*;
import io.testseer.backend.registry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalIndexTriggerServiceTest {

    @Mock LocalDirectoryFetcher localFetcher;
    @Mock ServiceRegistryService registryService;
    @Mock JavaParserService parserService;
    @Mock FactExtractor factExtractor;
    @Mock PeripheralDetector peripheralDetector;
    @Mock DualWriteService dualWriteService;
    @Mock GraphFactProjector graphProjector;
    @Mock AnalysisRunTracker runTracker;

    @InjectMocks LocalIndexTriggerService service;

    private ServiceEntry entry(String serviceId) {
        return new ServiceEntry(serviceId, "acme", "orders", "orders",
                "service", "MAVEN", List.of("src/main/java"), List.of("src/test/java"),
                null, true, null, null);
    }

    @Test
    void trigger_autoRegisters_whenServiceNotKnown() {
        when(localFetcher.detectBuildTool("/workspace/orders")).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha("/workspace/orders")).thenReturn("abc123");
        when(localFetcher.fetchJavaFiles("/workspace/orders")).thenReturn(
                List.of(new GitHubSourceFetcher.FetchedFile("Foo.java", "class Foo {}"))
        );
        when(registryService.register(any())).thenReturn(entry("svc-new"));
        when(parserService.parse(any(), any())).thenReturn(
                new ParsedModel("Foo.java", null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), false, null,
                        null, List.of(), List.of()));
        when(factExtractor.extractSymbolFacts(any())).thenReturn(List.of());
        when(factExtractor.extractOutboundCallFacts(any())).thenReturn(List.of());
        when(factExtractor.extractUnsupportedConstructFacts(any())).thenReturn(List.of());
        when(peripheralDetector.detect(any())).thenReturn(List.of());

        LocalIndexTriggerResponse resp = service.trigger(
                new LocalIndexTriggerRequest("acme", "/workspace/orders"));

        assertThat(resp.autoRegistered()).isTrue();
        assertThat(resp.serviceId()).isEqualTo("svc-new");
        assertThat(resp.serviceName()).isEqualTo("orders");
        assertThat(resp.commitSha()).isEqualTo("abc123");
        assertThat(resp.fileCount()).isEqualTo(1);
        verify(dualWriteService).write(any(), any());
        verify(graphProjector).project(any(), any());
        verify(runTracker).markComplete(any());
    }

    @Test
    void trigger_usesExistingService_whenAlreadyRegistered() {
        when(localFetcher.detectBuildTool(any())).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFiles(any())).thenReturn(List.of());
        when(registryService.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "orders", "orders"));
        when(registryService.getByOrgAndName("acme", "orders"))
                .thenReturn(Optional.of(entry("svc-existing")));

        LocalIndexTriggerResponse resp = service.trigger(
                new LocalIndexTriggerRequest("acme", "/workspace/orders"));

        assertThat(resp.autoRegistered()).isFalse();
        assertThat(resp.serviceId()).isEqualTo("svc-existing");
    }

    @Test
    void trigger_marksFailed_andRethrows_onPipelineError() {
        when(localFetcher.detectBuildTool(any())).thenReturn(null);
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFiles(any()))
                .thenThrow(new IllegalArgumentException("not a directory"));
        when(registryService.register(any())).thenReturn(entry("svc-001"));

        assertThatThrownBy(() ->
                service.trigger(new LocalIndexTriggerRequest("acme", "/bad/path")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(runTracker).markFailed(any(), any());
        verify(dualWriteService, never()).write(any(), any());
    }
}

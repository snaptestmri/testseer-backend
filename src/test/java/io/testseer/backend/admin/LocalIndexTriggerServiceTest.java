package io.testseer.backend.admin;

import io.testseer.backend.config.MavenProperties;
import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.ingestion.*;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import io.testseer.backend.registry.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalIndexTriggerServiceTest {

    @Mock LocalDirectoryFetcher localFetcher;
    @Mock ConfigFileFetcher configFileFetcher;
    @Mock ServiceRegistryService registryService;
    @Mock IndexingOrchestrator indexingOrchestrator;
    @Mock AnalysisRunTracker runTracker;
    @Mock WorkspaceCatalogService workspaceCatalog;
    @Mock PomFileFetcher pomFileFetcher;
    @Mock MavenProperties mavenProperties;

    @InjectMocks LocalIndexTriggerService service;

    @BeforeEach
    void stubWorkspaceProfile() {
        when(workspaceCatalog.resolveIndexProfile(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(pomFileFetcher.fetchScoped(anyString(), anyList())).thenReturn(List.of());
        lenient().when(mavenProperties.isTreeResolutionEnabled()).thenReturn(true);
        lenient().when(mavenProperties.isBulkIndexTreeResolutionEnabled()).thenReturn(false);
    }

    private ServiceEntry entry(String serviceId) {
        return new ServiceEntry(serviceId, "acme", "orders", "orders",
                "service", "MAVEN", List.of("src/main/java"), List.of("src/test/java"),
                null, true, null, null);
    }

    @Test
    void trigger_autoRegisters_whenServiceNotKnown() {
        when(localFetcher.detectBuildTool("/workspace/orders")).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha("/workspace/orders")).thenReturn("abc123");
        when(localFetcher.fetchJavaFilesFromRoots(eq("/workspace/orders"), anyList())).thenReturn(
                List.of(new GitHubSourceFetcher.FetchedFile("Foo.java", "class Foo {}"))
        );
        when(configFileFetcher.fetchFromDirectory("/workspace/orders")).thenReturn(List.of());
        when(registryService.register(any())).thenReturn(entry("svc-new"));
        when(indexingOrchestrator.index(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IndexingOrchestrator.IndexingResult(
                        List.of(), FactBatch.core("j", "acme", "orders", "svc-new", "abc123", "BASELINE",
                                List.of(), List.of(), List.of(), List.of()),
                        1, 0));

        LocalIndexTriggerResponse resp = service.trigger(
                new LocalIndexTriggerRequest("acme", "/workspace/orders", null, null, null, null));

        assertThat(resp.autoRegistered()).isTrue();
        assertThat(resp.serviceId()).isEqualTo("svc-new");
        assertThat(resp.fileCount()).isEqualTo(1);
        verify(indexingOrchestrator).index(any(), eq("acme"), eq("orders"), eq("svc-new"),
                eq("abc123"), eq("BASELINE"), any(), any(), any(), any(), any(), eq("/workspace/orders"), any());
        verify(runTracker).markComplete(any());
    }

    @Test
    void trigger_usesExistingService_whenAlreadyRegistered() {
        when(localFetcher.detectBuildTool(any())).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFilesFromRoots(anyString(), anyList())).thenReturn(List.of());
        when(configFileFetcher.fetchFromDirectory(any())).thenReturn(List.of());
        when(registryService.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "orders", "orders"));
        when(registryService.getByOrgRepoAndName("acme", "orders", "orders"))
                .thenReturn(Optional.of(entry("svc-existing")));
        when(indexingOrchestrator.index(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IndexingOrchestrator.IndexingResult(
                        List.of(), FactBatch.core("j", "acme", "orders", "svc-existing", "sha1", "BASELINE",
                                List.of(), List.of(), List.of(), List.of()),
                        0, 0));

        LocalIndexTriggerResponse resp = service.trigger(
                new LocalIndexTriggerRequest("acme", "/workspace/orders", null, null, null, null));

        assertThat(resp.autoRegistered()).isFalse();
        assertThat(resp.serviceId()).isEqualTo("svc-existing");
    }

    @Test
    void trigger_usesConfigRoots_whenServiceModuleDeclaresThem() {
        when(workspaceCatalog.resolveIndexProfile(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new WorkspaceCatalogService.IndexProfile(
                        "transaction-eval-suite",
                        "platform-transaction-eval-consumer",
                        "transaction-eval-suite",
                        "service",
                        List.of("evaluation-consumers/transaction-eval-consumer/src/main/java"),
                        false,
                        false,
                        List.of("platform-data"),
                        List.of("evaluation-consumers/transaction-eval-consumer/kubernetes-manifests"))));
        when(localFetcher.detectBuildTool(any())).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFilesFromRoots(anyString(), anyList())).thenReturn(List.of());
        when(configFileFetcher.fetchFromRoots(eq("/workspace/eval"), anyList())).thenReturn(
                List.of(new YamlPubSubExtractor.ConfigFile("dev.config-map.yaml", "kind: ConfigMap")));
        when(registryService.register(any())).thenReturn(entry("svc-eval"));
        when(indexingOrchestrator.index(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IndexingOrchestrator.IndexingResult(
                        List.of(), FactBatch.core("j", "quotient", "platform-transaction-eval-consumer", "svc-eval",
                                "sha1", "BASELINE", List.of(), List.of(), List.of(), List.of()),
                        0, 1));

        LocalIndexTriggerResponse resp = service.trigger(new LocalIndexTriggerRequest(
                "quotient", "/workspace/eval", null, "transaction-eval-suite", null, null));

        assertThat(resp.configFileCount()).isEqualTo(1);
        verify(configFileFetcher).fetchFromRoots(
                eq("/workspace/eval"),
                eq(List.of("evaluation-consumers/transaction-eval-consumer/kubernetes-manifests")));
        verify(configFileFetcher, never()).fetchFromDirectory(any());
    }

    @Test
    void trigger_reportsConfigFileCount_forProtoCatalogLibrary() {
        when(workspaceCatalog.resolveIndexProfile(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new WorkspaceCatalogService.IndexProfile(
                        "platform-msg-events", "optimus-platform-msg-framework", "platform-msg-events",
                        "library", List.of("platform-msg-events/src/main/resources/protobuf"),
                        false, false, List.of(), List.of())));
        when(localFetcher.detectBuildTool(any())).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFilesFromRoots(anyString(), anyList())).thenReturn(List.of());
        when(configFileFetcher.fetchFromRoots(anyString(), anyList())).thenReturn(
                List.of(new YamlPubSubExtractor.ConfigFile("offer.proto", "syntax = \"proto3\";"))
        );
        when(registryService.register(any())).thenReturn(entry("svc-proto"));
        when(indexingOrchestrator.index(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IndexingOrchestrator.IndexingResult(
                        List.of(), FactBatch.core("j", "quotient", "optimus-platform-msg-framework", "svc-proto",
                                "sha1", "BASELINE", List.of(), List.of(), List.of(), List.of()),
                        0, 1));

        LocalIndexTriggerResponse resp = service.trigger(new LocalIndexTriggerRequest(
                "quotient", "/workspace/optimus-platform-msg-framework", "platform-msg-events", null, null, null));

        assertThat(resp.fileCount()).isZero();
        assertThat(resp.configFileCount()).isEqualTo(1);
    }

    @Test
    void trigger_reportsOpenApiAndDdlFileCounts() {
        when(workspaceCatalog.resolveIndexProfile(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(new WorkspaceCatalogService.IndexProfile(
                        "apis-lib", "riq-platform-apis-optimus", "riq-platform-apis-optimus",
                        "catalog", List.of("reference"), false, true, List.of(), List.of())));
        when(localFetcher.detectBuildTool(any())).thenReturn("UNKNOWN");
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFilesFromRoots(anyString(), anyList())).thenReturn(List.of());
        when(localFetcher.fetchJsonFilesFromRoots(anyString(), anyList())).thenReturn(
                List.of(new GitHubSourceFetcher.FetchedFile("Offers.json", "{}"))
        );
        when(registryService.register(any())).thenReturn(entry("svc-apis"));
        when(indexingOrchestrator.index(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IndexingOrchestrator.IndexingResult(
                        List.of(), FactBatch.core("j", "quotient", "riq-platform-apis-optimus", "svc-apis",
                                "sha1", "BASELINE", List.of(), List.of(), List.of(), List.of()),
                        0, 0));

        LocalIndexTriggerResponse resp = service.trigger(new LocalIndexTriggerRequest(
                "quotient", "/workspace/riq-platform-apis-optimus", "optimus-platform-apis", null, null, null));

        assertThat(resp.fileCount()).isZero();
        assertThat(resp.openApiFileCount()).isEqualTo(1);
        assertThat(resp.ddlFileCount()).isZero();
    }

    @Test
    void trigger_marksFailed_andRethrows_onPipelineError() {
        when(localFetcher.detectBuildTool(any())).thenReturn(null);
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFilesFromRoots(anyString(), anyList()))
                .thenThrow(new IllegalArgumentException("not a directory"));
        when(registryService.register(any())).thenReturn(entry("svc-001"));

        assertThatThrownBy(() ->
                service.trigger(new LocalIndexTriggerRequest("acme", "/bad/path", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(runTracker).markFailed(any(), any());
        verify(indexingOrchestrator, never()).index(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}

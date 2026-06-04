package io.testseer.backend.admin;

import io.testseer.backend.ingestion.AnalysisRunTracker;
import io.testseer.backend.ingestion.GitHubTreeFetcher;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexTriggerServiceTest {

    @Mock ServiceRegistryService registryService;
    @Mock GitHubTreeFetcher treeFetcher;
    @Mock KafkaJobPublisher publisher;
    @Mock AnalysisRunTracker runTracker;
    @Mock JdbcClient db;

    @InjectMocks IndexTriggerService service;

    private ServiceEntry entry() {
        return new ServiceEntry("svc-001", "acme", "orders", "orders",
                "service", "MAVEN", List.of("src/main/java"), List.of("src/test/java"),
                null, true, null, null);
    }

    @Test
    void trigger_withExplicitSha_publishesJobWithCorrectFields() {
        when(registryService.getById("svc-001")).thenReturn(entry());
        // in-flight check returns 0
        var spec = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any()).query(Integer.class).single()).thenReturn(0);
        when(treeFetcher.fetchJavaPaths("acme", "orders", "abc123"))
                .thenReturn(List.of("src/main/java/Foo.java", "src/main/java/Bar.java"));

        IndexTriggerResponse resp = service.trigger("svc-001",
                new IndexTriggerRequest("abc123"));

        assertThat(resp.commitSha()).isEqualTo("abc123");
        assertThat(resp.fileCount()).isEqualTo(2);
        assertThat(resp.serviceId()).isEqualTo("svc-001");
        assertThat(resp.jobId()).isNotBlank();

        ArgumentCaptor<IngestionJob> captor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(publisher).publishBatchJob(captor.capture());
        IngestionJob job = captor.getValue();
        assertThat(job.jobType()).isEqualTo("MANUAL");
        assertThat(job.changedFiles()).containsExactly(
                "src/main/java/Foo.java", "src/main/java/Bar.java");
    }

    @Test
    void trigger_withNullSha_resolvesHead() {
        when(registryService.getById("svc-001")).thenReturn(entry());
        var spec = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any()).query(Integer.class).single()).thenReturn(0);
        when(treeFetcher.resolveHeadSha("acme", "orders")).thenReturn("headsha");
        when(treeFetcher.fetchJavaPaths("acme", "orders", "headsha"))
                .thenReturn(List.of("src/main/java/Foo.java"));

        IndexTriggerResponse resp = service.trigger("svc-001", new IndexTriggerRequest(null));

        assertThat(resp.commitSha()).isEqualTo("headsha");
        verify(treeFetcher).resolveHeadSha("acme", "orders");
    }

    @Test
    void trigger_throws409_whenJobAlreadyInFlight() {
        when(registryService.getById("svc-001")).thenReturn(entry());
        var spec = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any()).query(Integer.class).single()).thenReturn(1);

        assertThatThrownBy(() -> service.trigger("svc-001", new IndexTriggerRequest("abc")))
                .isInstanceOf(JobAlreadyInFlightException.class);

        verify(publisher, never()).publishBatchJob(any());
    }
}

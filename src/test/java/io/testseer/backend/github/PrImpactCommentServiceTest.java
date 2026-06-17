package io.testseer.backend.github;

import io.testseer.backend.analysis.CommitIndexValidator;
import io.testseer.backend.analysis.GapDetectionService;
import io.testseer.backend.analysis.GapReport;
import io.testseer.backend.analysis.ImpactAnalysisService;
import io.testseer.backend.analysis.ImpactReport;
import io.testseer.backend.query.ConsistencyGapService;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrImpactCommentServiceTest {

    @Mock ImpactAnalysisService impactService;
    @Mock GapDetectionService gapService;
    @Mock ConsistencyGapService consistencyGapService;
    @Mock CommitIndexValidator indexValidator;
    @Mock ServiceRegistryService registryService;
    @Mock PrCommentPublisher publisher;

    PrImpactCommentService service;

    @BeforeEach
    void setUp() {
        service = new PrImpactCommentService(
                impactService, gapService, consistencyGapService,
                indexValidator, registryService, publisher, "");
    }

    @Test
    void onPrJobComplete_publishesCommentForIndexedServices() {
        when(publisher.isEnabled()).thenReturn(true);
        when(registryService.listAll()).thenReturn(List.of(serviceEntry("svc-1")));
        when(indexValidator.isIndexed("svc-1", "deadbeef")).thenReturn(true);
        when(impactService.buildReport("svc-1", "deadbeef")).thenReturn(emptyImpact("svc-1", "deadbeef"));
        when(gapService.buildReport("svc-1")).thenReturn(emptyGaps("svc-1"));
        when(consistencyGapService.computeGaps("quotient", "svc-1")).thenReturn(List.of());

        IngestionJob job = prJob("quotient", "orders", 12, "deadbeef", "svc-1");
        service.onPrJobComplete(job);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishOrUpdate(eq("quotient"), eq("orders"), eq(12), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PrCommentFormatter.MARKER);
        assertThat(bodyCaptor.getValue()).contains("svc-1");
    }

    @Test
    void onPrJobComplete_skipsNonPrJobs() {
        IngestionJob job = new IngestionJob(
                "job-1", "PUSH", "quotient", "orders", "svc-1", "deadbeef",
                List.of(), null, Instant.now(), 1, null);

        service.onPrJobComplete(job);

        verify(publisher, never()).publishOrUpdate(any(), any(), any(Integer.class), any());
    }

    @Test
    void onPrJobComplete_skipsWhenPublisherDisabled() {
        when(publisher.isEnabled()).thenReturn(false);

        service.onPrJobComplete(prJob("quotient", "orders", 12, "deadbeef", "svc-1"));

        verify(registryService, never()).listAll();
    }

    private static IngestionJob prJob(String orgId, String repo, int prNumber, String sha, String serviceId) {
        return new IngestionJob(
                "job-1", "PR", orgId, repo, serviceId, sha,
                List.of("src/main/java/Foo.java"), prNumber, Instant.now(), 1, null);
    }

    private static ServiceEntry serviceEntry(String serviceId) {
        return new ServiceEntry(
                serviceId, "quotient", "orders", "orders", "service",
                "maven", List.of("src/main/java"), List.of("src/test/java"),
                "team", true, null, null);
    }

    private static ImpactReport emptyImpact(String serviceId, String sha) {
        return ImpactReport.withoutArtifactImpact(serviceId, sha, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static GapReport emptyGaps(String serviceId) {
        return new GapReport(serviceId, null, 0, 0, 0, List.of());
    }
}

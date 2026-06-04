package io.testseer.backend.admin;

import io.testseer.backend.ingestion.AnalysisRunTracker;
import io.testseer.backend.ingestion.GitHubTreeFetcher;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IndexTriggerService {

    private final ServiceRegistryService registryService;
    private final GitHubTreeFetcher treeFetcher;
    private final KafkaJobPublisher publisher;
    private final AnalysisRunTracker runTracker;
    private final JdbcClient db;

    public IndexTriggerService(ServiceRegistryService registryService,
                                GitHubTreeFetcher treeFetcher,
                                KafkaJobPublisher publisher,
                                AnalysisRunTracker runTracker,
                                JdbcClient db) {
        this.registryService = registryService;
        this.treeFetcher     = treeFetcher;
        this.publisher       = publisher;
        this.runTracker      = runTracker;
        this.db              = db;
    }

    public IndexTriggerResponse trigger(String serviceId, IndexTriggerRequest request) {
        ServiceEntry svc = registryService.getById(serviceId);

        checkNoJobInFlight(serviceId);

        String commitSha = request.commitSha() != null
                ? request.commitSha()
                : treeFetcher.resolveHeadSha(svc.orgId(), svc.repo());

        List<String> javaPaths = treeFetcher.fetchJavaPaths(svc.orgId(), svc.repo(), commitSha);

        IngestionJob job = new IngestionJob(
                UUID.randomUUID().toString(),
                "MANUAL",
                svc.orgId(),
                svc.repo(),
                serviceId,
                commitSha,
                javaPaths,
                null,
                Instant.now(),
                1
        );

        runTracker.markQueued(job);
        publisher.publishBatchJob(job);

        return new IndexTriggerResponse(job.jobId(), serviceId, commitSha, javaPaths.size());
    }

    private void checkNoJobInFlight(String serviceId) {
        int count = db.sql("""
                SELECT COUNT(*) FROM analysis_runs
                WHERE service_id = :svcId AND status IN ('QUEUED', 'RUNNING')
                """)
                .param("svcId", serviceId)
                .query(Integer.class)
                .single();

        if (count > 0) throw new JobAlreadyInFlightException(serviceId);
    }
}

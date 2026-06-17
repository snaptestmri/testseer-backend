package io.testseer.backend.admin;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.ingestion.AnalysisRunTracker;
import io.testseer.backend.ingestion.GitHubTreeFetcher;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IndexTriggerService {

    private final ServiceRegistryService registryService;
    private final GitHubTreeFetcher treeFetcher;
    private final KafkaJobPublisher publisher;
    private final AnalysisRunTracker runTracker;
    private final JdbcClient db;
    private final WorkspaceCatalogService workspaceCatalog;

    public IndexTriggerService(ServiceRegistryService registryService,
                                GitHubTreeFetcher treeFetcher,
                                KafkaJobPublisher publisher,
                                AnalysisRunTracker runTracker,
                                JdbcClient db,
                                WorkspaceCatalogService workspaceCatalog) {
        this.registryService = registryService;
        this.treeFetcher     = treeFetcher;
        this.publisher       = publisher;
        this.runTracker      = runTracker;
        this.db              = db;
        this.workspaceCatalog = workspaceCatalog;
    }

    public IndexTriggerResponse trigger(String serviceId, IndexTriggerRequest request) {
        ServiceEntry svc = registryService.getById(serviceId);

        checkNoJobInFlight(serviceId);

        String commitSha = request.commitSha() != null
                ? request.commitSha()
                : treeFetcher.resolveHeadSha(svc.orgId(), svc.repo());

        List<String> indexPaths = new ArrayList<>(
                treeFetcher.fetchJavaPaths(svc.orgId(), svc.repo(), commitSha));
        Optional<WorkspaceConfig.CatalogLibraryConfig> catalog =
                workspaceCatalog.findCatalogLibraryByRepo(svc.orgId(), svc.repo());
        if (catalog.isPresent() && catalog.get().indexOpenApi()) {
            indexPaths.addAll(treeFetcher.fetchJsonPaths(
                    svc.orgId(), svc.repo(), commitSha, catalog.get().sourceRoots()));
        }
        List<String> changedFiles = List.copyOf(new LinkedHashSet<>(indexPaths));

        IngestionJob job = new IngestionJob(
                UUID.randomUUID().toString(),
                "MANUAL",
                svc.orgId(),
                svc.repo(),
                serviceId,
                commitSha,
                changedFiles,
                null,
                Instant.now(),
                1,
                null
        );

        publisher.publishBatchJob(job);

        return new IndexTriggerResponse(job.jobId(), serviceId, commitSha, changedFiles.size());
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

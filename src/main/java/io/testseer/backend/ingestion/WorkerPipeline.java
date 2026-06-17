package io.testseer.backend.ingestion;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.github.PrImpactCommentService;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import io.testseer.backend.observability.IndexingPhaseTimer;
import io.testseer.backend.observability.MdcContext;
import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WorkerPipeline {

    private static final Logger log = LoggerFactory.getLogger(WorkerPipeline.class);

    private final GitHubSourceFetcher fetcher;
    private final ConfigFileFetcher configFileFetcher;
    private final IndexingOrchestrator indexingOrchestrator;
    private final AnalysisRunTracker runTracker;
    private final WorkspaceCatalogService workspaceCatalog;
    private final PomFileFetcher pomFileFetcher;
    private final PrImpactCommentService prImpactCommentService;

    public WorkerPipeline(GitHubSourceFetcher fetcher,
                          ConfigFileFetcher configFileFetcher,
                          PomFileFetcher pomFileFetcher,
                          IndexingOrchestrator indexingOrchestrator,
                          AnalysisRunTracker runTracker,
                          WorkspaceCatalogService workspaceCatalog,
                          PrImpactCommentService prImpactCommentService) {
        this.fetcher = fetcher;
        this.configFileFetcher = configFileFetcher;
        this.pomFileFetcher = pomFileFetcher;
        this.indexingOrchestrator = indexingOrchestrator;
        this.runTracker = runTracker;
        this.workspaceCatalog = workspaceCatalog;
        this.prImpactCommentService = prImpactCommentService;
    }

    public void process(IngestionJob job) {
        MdcContext.putJob(job);
        IndexingPhaseTimer timer = new IndexingPhaseTimer();
        runTracker.markQueued(job);
        runTracker.markRunning(job.jobId());
        List<String> changed = job.changedFiles() != null ? job.changedFiles() : List.of();
        List<GitHubSourceFetcher.FetchedFile> javaFiles = changed.isEmpty()
                ? List.of()
                : fetcher.fetchJavaFiles(job.orgId(), job.repo(), job.commitSha(), changed);
        timer.lap("fetchJava");

        List<GitHubSourceFetcher.FetchedFile> ddlFiles = changed.isEmpty()
                ? List.of()
                : fetcher.fetchDdlFiles(job.orgId(), job.repo(), job.commitSha(), changed);
        timer.lap("fetchDdl");

        List<YamlPubSubExtractor.ConfigFile> configFiles =
                configFileFetcher.fetchFromGitHubPaths(
                        fetcher, job.orgId(), job.repo(), job.commitSha(),
                        configFileFetcher.filterConfigPaths(changed));
        timer.lap("fetchConfig");

        boolean indexOpenApi = workspaceCatalog.findCatalogLibraryByRepo(job.orgId(), job.repo())
                .map(c -> c.indexOpenApi())
                .orElse(false);
        List<GitHubSourceFetcher.FetchedFile> openApiFiles = indexOpenApi && !changed.isEmpty()
                ? fetcher.fetchJsonFiles(job.orgId(), job.repo(), job.commitSha(), changed)
                : List.of();
        timer.lap("fetchOpenApi");

        List<GitHubSourceFetcher.FetchedFile> pomFiles = pomFileFetcher.fetchPomFiles(
                fetcher, job.orgId(), job.repo(), job.commitSha(), changed,
                "NIGHTLY".equals(job.jobType()) || "MANUAL".equals(job.jobType()));
        timer.lap("fetchPom");

        String snapshotType = "NIGHTLY".equals(job.jobType()) || "MANUAL".equals(job.jobType())
                ? "BASELINE" : "DELTA";

        IndexingOrchestrator.IndexingResult result = indexingOrchestrator.index(
                job.jobId(), job.orgId(), job.repo(), job.serviceId(),
                job.commitSha(), snapshotType, javaFiles, configFiles, ddlFiles, openApiFiles, pomFiles, null);
        timer.lap("index");

        runTracker.markComplete(job.jobId());
        log.info("Job {} complete: {} java files, {} pubsub facts, pipeline totalMs={} {}",
                job.jobId(), result.javaFileCount(),
                result.batch().pubsubResourceFacts().size(),
                timer.elapsedMs(), timer.formatPhases());

        prImpactCommentService.onPrJobComplete(job);
    }
}

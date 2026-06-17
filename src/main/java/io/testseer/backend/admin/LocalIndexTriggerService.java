package io.testseer.backend.admin;

import io.testseer.backend.config.MavenProperties;
import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.ingestion.AnalysisRunTracker;
import io.testseer.backend.ingestion.ConfigFileFetcher;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import io.testseer.backend.ingestion.IndexingOrchestrator;
import io.testseer.backend.ingestion.PomFileFetcher;
import io.testseer.backend.ingestion.maven.MavenIndexOptions;
import io.testseer.backend.ingestion.maven.MavenModulePathResolver;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import io.testseer.backend.observability.IndexingPhaseTimer;
import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LocalIndexTriggerService {

    private static final Logger log = LoggerFactory.getLogger(LocalIndexTriggerService.class);

    private final LocalDirectoryFetcher localFetcher;
    private final ConfigFileFetcher configFileFetcher;
    private final ServiceRegistryService registryService;
    private final IndexingOrchestrator indexingOrchestrator;
    private final AnalysisRunTracker runTracker;
    private final WorkspaceCatalogService workspaceCatalog;
    private final PomFileFetcher pomFileFetcher;
    private final MavenProperties mavenProperties;

    public LocalIndexTriggerService(LocalDirectoryFetcher localFetcher,
                                     ConfigFileFetcher configFileFetcher,
                                     ServiceRegistryService registryService,
                                     IndexingOrchestrator indexingOrchestrator,
                                     AnalysisRunTracker runTracker,
                                     WorkspaceCatalogService workspaceCatalog,
                                     PomFileFetcher pomFileFetcher,
                                     MavenProperties mavenProperties) {
        this.localFetcher          = localFetcher;
        this.configFileFetcher     = configFileFetcher;
        this.registryService       = registryService;
        this.indexingOrchestrator  = indexingOrchestrator;
        this.runTracker            = runTracker;
        this.workspaceCatalog      = workspaceCatalog;
        this.pomFileFetcher        = pomFileFetcher;
        this.mavenProperties       = mavenProperties;
    }

    public LocalIndexTriggerResponse trigger(LocalIndexTriggerRequest request) {
        String repoFolder = Path.of(request.path()).getFileName().toString();
        String buildTool   = localFetcher.detectBuildTool(request.path());
        String commitSha   = localFetcher.resolveGitSha(request.path());

        Optional<WorkspaceCatalogService.IndexProfile> profile =
                workspaceCatalog.resolveIndexProfile(
                        request.orgId(), repoFolder, request.catalogLibraryId(), request.serviceModuleId());

        String serviceId;
        String serviceName;
        String repo;
        String moduleType;
        List<String> sourceRoots;
        List<String> configRoots;
        boolean indexDdl;
        boolean indexOpenApi;

        if (profile.isPresent()) {
            WorkspaceCatalogService.IndexProfile p = profile.get();
            serviceId = p.serviceId();
            serviceName = p.serviceName();
            repo = p.repo();
            moduleType = p.moduleType();
            sourceRoots = p.sourceRoots();
            configRoots = p.configRoots();
            indexDdl = p.indexDdl();
            indexOpenApi = p.indexOpenApi();
            log.info("Using workspace profile {} ({}) for {}", serviceId, moduleType, repoFolder);
        } else {
            serviceId = repoFolder;
            serviceName = repoFolder;
            repo = repoFolder;
            moduleType = "service";
            sourceRoots = List.of("src/main/java");
            configRoots = List.of();
            indexDdl = false;
            indexOpenApi = false;
        }

        boolean autoRegistered = false;
        ServiceEntry svc;
        try {
            svc = registryService.register(new RegistrationRequest(
                    request.orgId(),
                    repo,
                    serviceName,
                    buildTool != null ? buildTool : "UNKNOWN",
                    moduleType,
                    sourceRoots,
                    List.of("src/test/java"),
                    null
            ));
            autoRegistered = true;
            serviceId = svc.serviceId();
            log.info("Auto-registered service {}/{} as {}", request.orgId(), serviceName, moduleType);
        } catch (DuplicateServiceException ex) {
            svc = registryService.getByOrgRepoAndName(request.orgId(), repo, serviceName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Service exists but could not be found: " + repo + "/" + serviceName));
            serviceId = svc.serviceId();
        }

        String jobId = UUID.randomUUID().toString();
        IngestionJob job = new IngestionJob(
                jobId, "LOCAL", request.orgId(), repo,
                serviceId, commitSha, List.of(), null, Instant.now(), 1, null
        );

        runTracker.markQueued(job);
        runTracker.markRunning(jobId);

        IndexingPhaseTimer timer = new IndexingPhaseTimer();
        try {
            List<GitHubSourceFetcher.FetchedFile> javaFiles =
                    localFetcher.fetchJavaFilesFromRoots(request.path(), sourceRoots);
            timer.lap("fetchJava");

            List<GitHubSourceFetcher.FetchedFile> ddlFiles = indexDdl
                    ? localFetcher.fetchDdlFiles(request.path())
                    : List.of();
            timer.lap("fetchDdl");

            List<GitHubSourceFetcher.FetchedFile> openApiFiles = indexOpenApi
                    ? localFetcher.fetchJsonFilesFromRoots(request.path(), sourceRoots)
                    : List.of();
            timer.lap("fetchOpenApi");

            List<YamlPubSubExtractor.ConfigFile> configFiles =
                    "service".equalsIgnoreCase(moduleType)
                            ? resolveServiceConfigFiles(request.path(), configRoots)
                            : "library".equalsIgnoreCase(moduleType)
                                    ? configFileFetcher.fetchFromRoots(request.path(), sourceRoots)
                                    : List.of();
            timer.lap("fetchConfig");

            List<String> pomRoots = MavenModulePathResolver.pomRootsFromProfile(sourceRoots, configRoots);
            List<GitHubSourceFetcher.FetchedFile> pomFiles =
                    pomFileFetcher.fetchScoped(request.path(), pomRoots);
            timer.lap("fetchPom");

            boolean treeResolution = resolveTreeResolution(request);
            MavenIndexOptions mavenOptions = new MavenIndexOptions(pomRoots, treeResolution);

            IndexingOrchestrator.IndexingResult result = indexingOrchestrator.index(
                    jobId, request.orgId(), repo, serviceId,
                    commitSha, "BASELINE", javaFiles, configFiles, ddlFiles, openApiFiles,
                    pomFiles, request.path(), mavenOptions);
            timer.lap("index");

            runTracker.markComplete(jobId);
            log.info("Local index complete for {}: {} java, {} config, {} consistency scenarios, totalMs={} {}",
                    serviceName, result.javaFileCount(), result.configFileCount(),
                    result.batch().consistencyScenarioFacts().size(),
                    timer.elapsedMs(), timer.formatPhases());

            return new LocalIndexTriggerResponse(
                    serviceId, serviceName, commitSha, result.javaFileCount(),
                    result.configFileCount(), openApiFiles.size(), ddlFiles.size(), autoRegistered);

        } catch (Exception ex) {
            runTracker.markFailed(jobId, ex.getMessage());
            throw ex;
        }
    }

    private List<YamlPubSubExtractor.ConfigFile> resolveServiceConfigFiles(
            String repoPath, List<String> configRoots) {
        if (configRoots != null && !configRoots.isEmpty()) {
            return configFileFetcher.fetchFromRoots(repoPath, configRoots);
        }
        return configFileFetcher.fetchFromDirectory(repoPath);
    }

    private boolean resolveTreeResolution(LocalIndexTriggerRequest request) {
        if (request.mavenTreeResolution() != null) {
            return request.mavenTreeResolution();
        }
        if (Boolean.TRUE.equals(request.bulkIndex())) {
            return mavenProperties.isBulkIndexTreeResolutionEnabled();
        }
        return mavenProperties.isTreeResolutionEnabled();
    }
}

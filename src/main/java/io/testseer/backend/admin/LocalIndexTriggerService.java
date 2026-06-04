package io.testseer.backend.admin;

import io.testseer.backend.ingestion.*;
import io.testseer.backend.registry.*;
import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LocalIndexTriggerService {

    private static final Logger log = LoggerFactory.getLogger(LocalIndexTriggerService.class);

    private final LocalDirectoryFetcher localFetcher;
    private final ServiceRegistryService registryService;
    private final JavaParserService parserService;
    private final FactExtractor factExtractor;
    private final PeripheralDetector peripheralDetector;
    private final DualWriteService dualWriteService;
    private final AnalysisRunTracker runTracker;

    public LocalIndexTriggerService(LocalDirectoryFetcher localFetcher,
                                     ServiceRegistryService registryService,
                                     JavaParserService parserService,
                                     FactExtractor factExtractor,
                                     PeripheralDetector peripheralDetector,
                                     DualWriteService dualWriteService,
                                     AnalysisRunTracker runTracker) {
        this.localFetcher      = localFetcher;
        this.registryService   = registryService;
        this.parserService     = parserService;
        this.factExtractor     = factExtractor;
        this.peripheralDetector = peripheralDetector;
        this.dualWriteService  = dualWriteService;
        this.runTracker        = runTracker;
    }

    public LocalIndexTriggerResponse trigger(LocalIndexTriggerRequest request) {
        String serviceName = Path.of(request.path()).getFileName().toString();
        String buildTool   = localFetcher.detectBuildTool(request.path());
        String commitSha   = localFetcher.resolveGitSha(request.path());

        boolean autoRegistered = false;
        ServiceEntry svc;
        try {
            svc = registryService.register(new RegistrationRequest(
                    request.orgId(),
                    serviceName,
                    serviceName,
                    buildTool != null ? buildTool : "UNKNOWN",
                    "service",
                    List.of("src/main/java"),
                    List.of("src/test/java"),
                    null
            ));
            autoRegistered = true;
            log.info("Auto-registered service {}/{}", request.orgId(), serviceName);
        } catch (DuplicateServiceException ex) {
            svc = registryService.getByOrgAndName(request.orgId(), serviceName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Service exists but could not be found: " + serviceName));
        }

        String jobId = UUID.randomUUID().toString();
        IngestionJob job = new IngestionJob(
                jobId, "LOCAL", request.orgId(), serviceName,
                svc.serviceId(), commitSha, List.of(), null, Instant.now(), 1
        );

        runTracker.markQueued(job);
        runTracker.markRunning(jobId);

        try {
            List<GitHubSourceFetcher.FetchedFile> files =
                    localFetcher.fetchJavaFiles(request.path());

            List<ParsedModel> models = files.stream()
                    .map(f -> parserService.parse(f.path(), f.content()))
                    .toList();

            var symbolFacts     = models.stream().flatMap(m -> factExtractor.extractSymbolFacts(m).stream()).toList();
            var outboundFacts   = models.stream().flatMap(m -> factExtractor.extractOutboundCallFacts(m).stream()).toList();
            var peripheralFacts = models.stream().flatMap(m -> peripheralDetector.detect(m).stream()).toList();
            var unsupported     = models.stream().flatMap(m -> factExtractor.extractUnsupportedConstructFacts(m).stream()).toList();

            FactBatch batch = new FactBatch(
                    jobId, request.orgId(), serviceName, svc.serviceId(),
                    commitSha, "BASELINE",
                    symbolFacts, outboundFacts, peripheralFacts, unsupported
            );

            dualWriteService.write(batch, models);
            runTracker.markComplete(jobId);
            log.info("Local index complete for {}: {} files, {} symbol facts",
                    serviceName, files.size(), symbolFacts.size());

            return new LocalIndexTriggerResponse(
                    svc.serviceId(), serviceName, commitSha, files.size(), autoRegistered);

        } catch (Exception ex) {
            runTracker.markFailed(jobId, ex.getMessage());
            throw ex;
        }
    }
}

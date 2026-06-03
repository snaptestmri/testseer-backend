package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WorkerPipeline {

    private static final Logger log = LoggerFactory.getLogger(WorkerPipeline.class);

    private final GitHubSourceFetcher fetcher;
    private final JavaParserService parserService;
    private final FactExtractor factExtractor;
    private final PeripheralDetector peripheralDetector;
    private final DualWriteService dualWriteService;

    public WorkerPipeline(GitHubSourceFetcher fetcher,
                          JavaParserService parserService,
                          FactExtractor factExtractor,
                          PeripheralDetector peripheralDetector,
                          DualWriteService dualWriteService) {
        this.fetcher = fetcher;
        this.parserService = parserService;
        this.factExtractor = factExtractor;
        this.peripheralDetector = peripheralDetector;
        this.dualWriteService = dualWriteService;
    }

    public void process(IngestionJob job) {
        List<GitHubSourceFetcher.FetchedFile> files = fetcher.fetchJavaFiles(
                job.orgId(), job.repo(), job.commitSha(), job.changedFiles());

        List<ParsedModel> models = files.stream()
                .map(f -> parserService.parse(f.path(), f.content()))
                .toList();

        List<FactBatch.SymbolFact> symbolFacts = models.stream()
                .flatMap(m -> factExtractor.extractSymbolFacts(m).stream())
                .toList();
        List<FactBatch.OutboundCallFact> outboundFacts = models.stream()
                .flatMap(m -> factExtractor.extractOutboundCallFacts(m).stream())
                .toList();
        List<FactBatch.PeripheralFact> peripheralFacts = models.stream()
                .flatMap(m -> peripheralDetector.detect(m).stream())
                .toList();
        List<FactBatch.UnsupportedConstructFact> unsupported = models.stream()
                .flatMap(m -> factExtractor.extractUnsupportedConstructFacts(m).stream())
                .toList();

        if (!unsupported.isEmpty()) {
            log.warn("Job {} has {} unsupported constructs in service {}",
                    job.jobId(), unsupported.size(), job.serviceId());
        }

        String snapshotType = "NIGHTLY".equals(job.jobType()) ? "BASELINE" : "DELTA";

        FactBatch batch = new FactBatch(
                job.jobId(), job.orgId(), job.repo(), job.serviceId(),
                job.commitSha(), snapshotType,
                symbolFacts, outboundFacts, peripheralFacts, unsupported
        );

        dualWriteService.write(batch, models);
        log.info("Job {} complete: {} symbol facts, {} peripheral facts",
                job.jobId(), symbolFacts.size(), peripheralFacts.size());
    }
}

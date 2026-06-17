package io.testseer.backend.ingestion;

import io.testseer.backend.graph.ComponentScanWiringProjector;
import io.testseer.backend.graph.EntryTriggerGraphProjector;
import io.testseer.backend.graph.ExternalEndpointGraphProjector;
import io.testseer.backend.graph.GraphFactProjector;
import io.testseer.backend.graph.MavenGraphProjector;
import io.testseer.backend.graph.MessagingGraphProjector;
import io.testseer.backend.ingestion.catalog.CatalogFactOrchestrator;
import io.testseer.backend.ingestion.catalog.EndpointPathEnricher;
import io.testseer.backend.ingestion.catalog.SchemaDdlExtractor;
import io.testseer.backend.ingestion.maven.MavenFactOrchestrator;
import io.testseer.backend.ingestion.maven.MavenIndexOptions;
import io.testseer.backend.ingestion.messaging.DlqRetryPathExtractor;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import io.testseer.backend.ingestion.consistency.ConsistencyFactOrchestrator;
import io.testseer.backend.config.ContractProperties;
import io.testseer.backend.config.MavenProperties;
import io.testseer.backend.ingestion.contract.OpenApiContractOrchestrator;
import io.testseer.backend.ingestion.testcalls.TestHttpCallOrchestrator;
import io.testseer.backend.ingestion.triggers.EntryTriggerOrchestrator;
import io.testseer.backend.observability.IndexingPhaseTimer;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IndexingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IndexingOrchestrator.class);

    private final JavaParserService parserService;
    private final MessagingFactOrchestrator messagingFactOrchestrator;
    private final CatalogFactOrchestrator catalogFactOrchestrator;
    private final ServiceRegistryService registryService;
    private final DualWriteService dualWriteService;
    private final GraphFactProjector graphProjector;
    private final MessagingGraphProjector messagingGraphProjector;
    private final ExternalEndpointGraphProjector externalEndpointGraphProjector;
    private final EntryTriggerOrchestrator entryTriggerOrchestrator;
    private final ConsistencyFactOrchestrator consistencyFactOrchestrator;
    private final EntryTriggerGraphProjector entryTriggerGraphProjector;
    private final ComponentScanWiringProjector componentScanWiringProjector;
    private final SchemaDdlExtractor schemaDdlExtractor;
    private final OpenApiContractOrchestrator openApiContractOrchestrator;
    private final TestHttpCallOrchestrator testHttpCallOrchestrator;
    private final ContractProperties contractProperties;
    private final DlqRetryPathExtractor dlqRetryPathExtractor;
    private final MavenFactOrchestrator mavenFactOrchestrator;
    private final MavenGraphProjector mavenGraphProjector;
    private final MavenProperties mavenProperties;
    private final EndpointPathEnricher endpointPathEnricher;

    public IndexingOrchestrator(
            JavaParserService parserService,
            MessagingFactOrchestrator messagingFactOrchestrator,
            CatalogFactOrchestrator catalogFactOrchestrator,
            ServiceRegistryService registryService,
            DualWriteService dualWriteService,
            GraphFactProjector graphProjector,
            MessagingGraphProjector messagingGraphProjector,
            ExternalEndpointGraphProjector externalEndpointGraphProjector,
            EntryTriggerOrchestrator entryTriggerOrchestrator,
            ConsistencyFactOrchestrator consistencyFactOrchestrator,
            EntryTriggerGraphProjector entryTriggerGraphProjector,
            ComponentScanWiringProjector componentScanWiringProjector,
            SchemaDdlExtractor schemaDdlExtractor,
            OpenApiContractOrchestrator openApiContractOrchestrator,
            TestHttpCallOrchestrator testHttpCallOrchestrator,
            ContractProperties contractProperties,
            DlqRetryPathExtractor dlqRetryPathExtractor,
            MavenFactOrchestrator mavenFactOrchestrator,
            MavenGraphProjector mavenGraphProjector,
            MavenProperties mavenProperties,
            EndpointPathEnricher endpointPathEnricher) {
        this.parserService = parserService;
        this.messagingFactOrchestrator = messagingFactOrchestrator;
        this.catalogFactOrchestrator = catalogFactOrchestrator;
        this.registryService = registryService;
        this.dualWriteService = dualWriteService;
        this.graphProjector = graphProjector;
        this.messagingGraphProjector = messagingGraphProjector;
        this.externalEndpointGraphProjector = externalEndpointGraphProjector;
        this.entryTriggerOrchestrator = entryTriggerOrchestrator;
        this.consistencyFactOrchestrator = consistencyFactOrchestrator;
        this.entryTriggerGraphProjector = entryTriggerGraphProjector;
        this.componentScanWiringProjector = componentScanWiringProjector;
        this.schemaDdlExtractor = schemaDdlExtractor;
        this.openApiContractOrchestrator = openApiContractOrchestrator;
        this.testHttpCallOrchestrator = testHttpCallOrchestrator;
        this.contractProperties = contractProperties;
        this.dlqRetryPathExtractor = dlqRetryPathExtractor;
        this.mavenFactOrchestrator = mavenFactOrchestrator;
        this.mavenGraphProjector = mavenGraphProjector;
        this.mavenProperties = mavenProperties;
        this.endpointPathEnricher = endpointPathEnricher;
    }

    public IndexingResult index(
            String jobId,
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<GitHubSourceFetcher.FetchedFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> configFiles) {
        return index(jobId, orgId, repo, serviceId, commitSha, snapshotType, javaFiles, configFiles, List.of(), List.of(), List.of(), null);
    }

    public IndexingResult index(
            String jobId,
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<GitHubSourceFetcher.FetchedFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<GitHubSourceFetcher.FetchedFile> ddlFiles) {
        return index(jobId, orgId, repo, serviceId, commitSha, snapshotType, javaFiles, configFiles, ddlFiles, List.of(), List.of(), null);
    }

    public IndexingResult index(
            String jobId,
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<GitHubSourceFetcher.FetchedFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<GitHubSourceFetcher.FetchedFile> ddlFiles,
            List<GitHubSourceFetcher.FetchedFile> openApiFiles) {
        return index(jobId, orgId, repo, serviceId, commitSha, snapshotType, javaFiles, configFiles, ddlFiles, openApiFiles, List.of(), null);
    }

    public IndexingResult index(
            String jobId,
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<GitHubSourceFetcher.FetchedFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<GitHubSourceFetcher.FetchedFile> ddlFiles,
            List<GitHubSourceFetcher.FetchedFile> openApiFiles,
            List<GitHubSourceFetcher.FetchedFile> pomFiles,
            String repoLocalPath) {
        return index(jobId, orgId, repo, serviceId, commitSha, snapshotType, javaFiles, configFiles,
                ddlFiles, openApiFiles, pomFiles, repoLocalPath, null);
    }

    public IndexingResult index(
            String jobId,
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<GitHubSourceFetcher.FetchedFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<GitHubSourceFetcher.FetchedFile> ddlFiles,
            List<GitHubSourceFetcher.FetchedFile> openApiFiles,
            List<GitHubSourceFetcher.FetchedFile> pomFiles,
            String repoLocalPath,
            MavenIndexOptions mavenIndexOptions) {

        IndexingPhaseTimer timer = new IndexingPhaseTimer();

        List<MessagingFactOrchestrator.SourceFile> sources = new ArrayList<>();
        for (GitHubSourceFetcher.FetchedFile file : javaFiles) {
            ParsedModel model = KotlinSourceLightParser.isKotlinPath(file.path())
                    ? kotlinParsedModel(file.path(), file.content())
                    : parserService.parse(file.path(), file.content());
            sources.add(new MessagingFactOrchestrator.SourceFile(
                    file.path(), file.content(), model
            ));
        }
        sources = endpointPathEnricher.enrich(sources);
        timer.lap("parse");

        MessagingFactOrchestrator.IndexingFacts facts = messagingFactOrchestrator.buildFacts(
                jobId, orgId, repo, serviceId, commitSha, snapshotType, sources, configFiles
        );
        timer.lap("messagingFacts");

        FactBatch batch = facts.batch();
        ServiceEntry svc = registryService.getById(serviceId);
        boolean libraryIndex = "library".equalsIgnoreCase(svc.moduleType())
                || (openApiFiles != null && !openApiFiles.isEmpty());
        if (libraryIndex) {
            CatalogFactOrchestrator.CatalogFacts catalog = catalogFactOrchestrator.build(
                    orgId, repo, serviceId, commitSha, snapshotType, sources);
            List<FactBatch.SchemaObjectFact> schemaFacts = schemaDdlExtractor.extract(
                    ddlFiles != null ? ddlFiles : List.of());
            batch = batch.withCatalogFacts(
                    catalog.dataObjectFacts(), catalog.accessorMethodFacts(), schemaFacts);
            batch = batch.withConsistencyScenarios(
                    consistencyFactOrchestrator.buildFromLibraryIndex(catalog.dataObjectFacts()));
            if (openApiFiles != null && !openApiFiles.isEmpty()) {
                OpenApiContractOrchestrator.ContractFacts contractFacts =
                        openApiContractOrchestrator.build(openApiFiles);
                batch = batch.withContractFacts(
                        contractFacts.operationFacts(), contractFacts.schemaFacts());
            }
        } else {
            batch = batch.withEntryTriggers(
                    entryTriggerOrchestrator.buildFromModels(
                            facts.models(), sources, configFiles,
                            batch.pubsubResourceFacts(), "unknown",
                            svc.serviceName(), svc.repo()));
            batch = batch.withConsistencyScenarios(
                    consistencyFactOrchestrator.buildFromServiceIndex(batch.dataAccessFacts()));
            if (contractProperties.isTestSuiteRepo(repo)) {
                batch = batch.withTestHttpCallFacts(testHttpCallOrchestrator.buildFromSources(sources));
            }
            batch = batch.withAsyncRetryPaths(dlqRetryPathExtractor.extract(configFiles));
        }
        timer.lap(libraryIndex ? "libraryEnrichment" : "serviceEnrichment");

        MavenIndexOptions mavenOptions = mavenIndexOptions != null
                ? mavenIndexOptions
                : MavenIndexOptions.defaults(mavenProperties);
        MavenFactOrchestrator.MavenFacts mavenFacts = mavenFactOrchestrator.build(
                orgId, repo, serviceId, commitSha,
                svc.buildTool(),
                pomFiles != null ? pomFiles : List.of(),
                repoLocalPath,
                mavenOptions);
        batch = batch.withMavenFacts(mavenFacts.modules(), mavenFacts.dependencies());
        timer.lap("mavenFacts");

        dualWriteService.write(batch, facts.models());
        timer.lap("dualWrite");

        Map<String, String> sourceByClassFqn = new LinkedHashMap<>();
        Map<String, String> contentByPath = new LinkedHashMap<>();
        for (MessagingFactOrchestrator.SourceFile src : sources) {
            contentByPath.put(src.path(), src.content());
            ParsedModel model = src.parsedModel();
            if (model.classFqn() != null && !model.parseError()) {
                sourceByClassFqn.put(model.classFqn(), src.content());
            }
        }
        if (pomFiles != null) {
            for (GitHubSourceFetcher.FetchedFile pom : pomFiles) {
                contentByPath.put(pom.path(), pom.content());
            }
        }
        graphProjector.project(batch, facts.models(), sourceByClassFqn);
        timer.lap("graphProject");
        messagingGraphProjector.project(batch);
        timer.lap("messagingGraph");
        externalEndpointGraphProjector.project(batch);
        timer.lap("externalEndpointGraph");
        entryTriggerGraphProjector.project(batch);
        timer.lap("entryTriggerGraph");
        componentScanWiringProjector.project(batch, facts.models(), contentByPath);
        timer.lap("componentScanGraph");
        mavenGraphProjector.project(batch, mavenFacts);
        timer.lap("mavenGraph");

        log.info("Indexed {}: {} symbols, {} pubsub, {} schemas, {} gates, {} external endpoints, {} entry triggers, {} data objects, {} accessors, {} contract ops, {} maven modules, {} maven deps",
                serviceId,
                batch.symbolFacts().size(),
                batch.pubsubResourceFacts().size(),
                batch.messageSchemaFacts().size(),
                batch.flowGateFacts().size(),
                batch.externalEndpointFacts().size(),
                batch.entryTriggerFacts().size(),
                batch.dataObjectFacts().size(),
                batch.accessorMethodFacts().size(),
                batch.contractOperationFacts().size(),
                batch.mavenModuleFacts().size(),
                batch.mavenDependencyFacts().size());
        log.info("Index timing jobId={} serviceId={} javaFiles={} configFiles={} totalMs={} {}",
                jobId, serviceId, javaFiles.size(), configFiles.size(),
                timer.elapsedMs(), timer.formatPhases());

        return new IndexingResult(facts.models(), batch, javaFiles.size(), configFiles.size());
    }

    private static ParsedModel kotlinParsedModel(String filePath, String content) {
        return KotlinSourceLightParser.firstTopLevelTypeFqn(filePath, content)
                .map(fqn -> ParsedModel.of(
                        filePath, fqn, List.of(), List.of(), List.of(), List.of(), List.of(),
                        false, null, null, List.of(), List.of()))
                .orElseGet(() -> ParsedModel.of(
                        filePath, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                        false, null, null, List.of(), List.of()));
    }

    public record IndexingResult(
            List<ParsedModel> models,
            FactBatch batch,
            int javaFileCount,
            int configFileCount
    ) {}
}

package io.testseer.backend.ingestion.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.ContractRulePack;
import io.testseer.backend.config.ContractRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OpenApiContractOrchestrator {

    private final OpenApiSpecParser specParser;
    private final JsonSchemaSummaryExtractor schemaSummaryExtractor;
    private final ContractRulePackLoader rulePackLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenApiContractOrchestrator(
            OpenApiSpecParser specParser,
            JsonSchemaSummaryExtractor schemaSummaryExtractor,
            ContractRulePackLoader rulePackLoader) {
        this.specParser = specParser;
        this.schemaSummaryExtractor = schemaSummaryExtractor;
        this.rulePackLoader = rulePackLoader;
    }

    public record ContractFacts(
            List<FactBatch.ContractOperationFact> operationFacts,
            List<FactBatch.ContractSchemaFact> schemaFacts
    ) {}

    public ContractFacts build(List<GitHubSourceFetcher.FetchedFile> jsonFiles) {
        if (jsonFiles == null || jsonFiles.isEmpty()) {
            return new ContractFacts(List.of(), List.of());
        }

        List<OpenApiSpecParser.SchemaFile> schemaFiles = new ArrayList<>();
        List<GitHubSourceFetcher.FetchedFile> specFiles = new ArrayList<>();

        for (GitHubSourceFetcher.FetchedFile file : jsonFiles) {
            if (file.path() == null || file.content() == null) continue;
            if (!file.path().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            if (isOpenApiSpec(file.content())) {
                specFiles.add(file);
            } else {
                schemaFiles.add(new OpenApiSpecParser.SchemaFile(file.path(), file.content()));
            }
        }

        Map<String, Map<String, Object>> schemaIndex = specParser.buildSchemaIndex(schemaFiles);
        ContractRulePack rulePack = rulePackLoader.getRulePack();

        List<FactBatch.ContractOperationFact> operations = new ArrayList<>();
        Set<String> seenOps = new LinkedHashSet<>();

        for (GitHubSourceFetcher.FetchedFile specFile : specFiles) {
            for (OpenApiSpecParser.ParsedOperation op : specParser.parseSpecFile(
                    specFile.path(), specFile.content(), schemaIndex)) {
                if (!seenOps.add(op.operationId())) continue;
                String mappedService = rulePack.primaryServiceForDomain(op.specDomain());
                operations.add(new FactBatch.ContractOperationFact(
                        op.operationId(),
                        op.specDomain(),
                        op.specFile(),
                        op.openapiVersion(),
                        op.operationIdOpenapi(),
                        op.httpMethod(),
                        op.pathTemplate(),
                        op.pathNormalized(),
                        op.summary(),
                        toJson(op.tags()),
                        op.requestSchemaRef(),
                        op.responseSchemaRef(),
                        schemaSummaryExtractor.fieldsJson(op.requestFieldSummary()),
                        schemaSummaryExtractor.fieldsJson(op.responseFieldSummary()),
                        toJson(op.serverUrls()),
                        mappedService,
                        "OPENAPI",
                        0.95,
                        "{}"
                ));
            }
        }

        List<FactBatch.ContractSchemaFact> schemas = new ArrayList<>();
        Set<String> seenSchemas = new LinkedHashSet<>();
        for (JsonSchemaSummaryExtractor.SchemaSummary summary : specParser.summarizeSchemas(schemaIndex)) {
            if (!seenSchemas.add(summary.schemaId())) continue;
            schemas.add(new FactBatch.ContractSchemaFact(
                    summary.schemaId(),
                    summary.title(),
                    summary.type(),
                    schemaSummaryExtractor.fieldsJson(summary.topLevelFields()),
                    schemaSummaryExtractor.fieldsJson(summary.requiredFields()),
                    schemaSummaryExtractor.fieldsJson(summary.nestedFieldPaths()),
                    summary.schemaId(),
                    "JSON_SCHEMA"
            ));
        }

        return new ContractFacts(operations, schemas);
    }

    private static boolean isOpenApiSpec(String content) {
        return content.contains("\"openapi\"") && content.contains("\"paths\"");
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }
}

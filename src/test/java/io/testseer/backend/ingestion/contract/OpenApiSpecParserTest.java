package io.testseer.backend.ingestion.contract;

import io.testseer.backend.config.ContractRulePack;
import io.testseer.backend.config.ContractRulePackLoader;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSpecParserTest {

    private final JsonSchemaSummaryExtractor schemaSummaryExtractor = new JsonSchemaSummaryExtractor();
    private final JsonSchemaNestedWalker nestedWalker = new JsonSchemaNestedWalker();
    private final OpenApiSpecParser parser = new OpenApiSpecParser(schemaSummaryExtractor, nestedWalker);
    private final ContractRulePackLoader rulePackLoader = new ContractRulePackLoader(
            new FileSystemResource("../config/rule-packs/quotient-api-contracts.yml"));
    private final OpenApiContractOrchestrator orchestrator = new OpenApiContractOrchestrator(
            parser, schemaSummaryExtractor, rulePackLoader);

    @Test
    void parseSpecFile_extractsOperationsAndSchemaSummaries() throws Exception {
        String specPath = "reference/Offers/Offers-APIs-fixture.v1.json";
        String spec = readFixture("fixtures/openapi/offers-spec.json");
        String allOffersSchema = readFixture("fixtures/openapi/AllOffersResponse.v1.json");
        String redeemRequest = readFixture("fixtures/openapi/RedeemRequest.v1.json");
        String redeemResponse = readFixture("fixtures/openapi/RedeemResponse.v1.json");

        List<GitHubSourceFetcher.FetchedFile> files = List.of(
                new GitHubSourceFetcher.FetchedFile(specPath, spec),
                new GitHubSourceFetcher.FetchedFile("reference/Offers/AllOffersResponse.v1.json", allOffersSchema),
                new GitHubSourceFetcher.FetchedFile("reference/Offers/RedeemRequest.v1.json", redeemRequest),
                new GitHubSourceFetcher.FetchedFile("reference/Offers/RedeemResponse.v1.json", redeemResponse)
        );

        var facts = orchestrator.build(files);

        assertThat(facts.operationFacts()).hasSize(2);
        assertThat(facts.operationFacts())
                .extracting(f -> f.operationIdOpenapi())
                .containsExactlyInAnyOrder("post-offers-all", "post-offers-redeem");

        var redeem = facts.operationFacts().stream()
                .filter(f -> "post-offers-redeem".equals(f.operationIdOpenapi()))
                .findFirst()
                .orElseThrow();
        assertThat(redeem.specDomain()).isEqualTo("Offers");
        assertThat(redeem.httpMethod()).isEqualTo("POST");
        assertThat(redeem.pathTemplate()).isEqualTo("/offers/redeem");
        assertThat(redeem.mappedServiceName()).isEqualTo("platform-optimus-offer-service");
        assertThat(redeem.requestFieldSummaryJson()).contains("offerIds");

        assertThat(facts.schemaFacts()).isNotEmpty();
        assertThat(facts.schemaFacts().stream()
                .filter(s -> s.schemaId().contains("RedeemRequest"))
                .findFirst()
                .orElseThrow()
                .nestedFieldPathsJson()).contains("offerIds");
    }

    @Test
    void inferSpecDomain_fromReferencePath() {
        assertThat(OpenApiSpecParser.inferSpecDomain("reference/Rebate/Rebate-APIs.v1.json"))
                .isEqualTo("Rebate");
    }

    @Test
    void normalizePath_replacesPathParams() {
        assertThat(OpenApiSpecParser.normalizePath("/offers/{offerId}/detail"))
                .isEqualTo("/offers/{*}/detail");
    }

    private static String readFixture(String path) throws Exception {
        return new String(OpenApiSpecParserTest.class.getClassLoader()
                .getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }
}

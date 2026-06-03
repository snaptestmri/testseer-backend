package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactExtractorTest {

    private final FactExtractor extractor = new FactExtractor();

    private static ParsedModel modelWithEndpoint(String httpMethod, String path) {
        return new ParsedModel(
                "OrderController.java",
                "com.example.OrderController",
                List.of("RestController", "RequestMapping"),
                List.of("OrderService"),
                List.of(),
                List.of(new ParsedModel.EndpointDef(httpMethod, path, "getOrder")),
                List.of(),
                false, null
        );
    }

    @Test
    void extractsEndpointAsSymbolFact() {
        ParsedModel model = modelWithEndpoint("GET", "/orders/{id}");

        List<FactBatch.SymbolFact> facts = extractor.extractSymbolFacts(model);

        assertThat(facts).anyMatch(f ->
                f.symbolKind().equals("ENDPOINT") &&
                f.symbolFqn().equals("com.example.OrderController#getOrder")
        );
    }

    @Test
    void extractsClassAsSymbolFact() {
        ParsedModel model = modelWithEndpoint("GET", "/orders");

        List<FactBatch.SymbolFact> facts = extractor.extractSymbolFacts(model);

        assertThat(facts).anyMatch(f ->
                f.symbolKind().equals("CLASS") &&
                f.symbolFqn().equals("com.example.OrderController")
        );
    }

    @Test
    void extractsOutboundCallFacts() {
        ParsedModel model = new ParsedModel(
                "OrderService.java", "com.example.OrderService",
                List.of(), List.of("RestClient"), List.of(),
                List.of(), List.of(new ParsedModel.OutboundCallDef("RestClient", "GET", "/inventory", "getInventory")),
                false, null
        );

        List<FactBatch.OutboundCallFact> facts = extractor.extractOutboundCallFacts(model);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).sourceSymbol()).isEqualTo("com.example.OrderService");
        assertThat(facts.get(0).evidenceSource()).isEqualTo("javaparser");
    }

    @Test
    void unsupportedConstruct_emittedForParseError() {
        ParsedModel errorModel = new ParsedModel(
                "Bad.java", null, List.of(), List.of(), List.of(),
                List.of(), List.of(), true, "complex annotation"
        );

        List<FactBatch.UnsupportedConstructFact> facts =
                extractor.extractUnsupportedConstructFacts(errorModel);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).filePath()).isEqualTo("Bad.java");
        assertThat(facts.get(0).reasonCode()).isEqualTo("PARSE_ERROR");
    }
}

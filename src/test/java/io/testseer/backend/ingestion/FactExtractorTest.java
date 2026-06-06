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
                false, null,
                null, List.of(), List.of()
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
                false, null,
                null, List.of(), List.of()
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
                List.of(), List.of(), true, "complex annotation",
                null, List.of(), List.of()
        );

        List<FactBatch.UnsupportedConstructFact> facts =
                extractor.extractUnsupportedConstructFacts(errorModel);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).filePath()).isEqualTo("Bad.java");
        assertThat(facts.get(0).reasonCode()).isEqualTo("PARSE_ERROR");
    }

    @Test
    void extractMethodFacts_emitsMethodSymbols() {
        ParsedModel model = new ParsedModel(
                "OrderService.java", "io.orders.OrderService",
                List.of(), List.of(), List.of(), List.of(), List.of(), false, null,
                "Manages orders",
                List.of(new ParsedModel.MethodDef(
                        "createOrder", "Creates an order",
                        "Order", List.of("String", "Money"),
                        List.of("PaymentException")
                )),
                List.of()
        );

        List<FactBatch.SymbolFact> facts = extractor.extractMethodFacts(model);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).symbolFqn()).isEqualTo("io.orders.OrderService#createOrder");
        assertThat(facts.get(0).symbolKind()).isEqualTo("METHOD");
        assertThat(facts.get(0).attributes()).contains("PaymentException");
    }

    @Test
    void extractEnumFacts_emitsEnumSymbol() {
        ParsedModel model = new ParsedModel(
                "OrderStatus.java", "io.orders.OrderStatus",
                List.of(), List.of(), List.of(), List.of(), List.of(), false, null,
                "Order lifecycle states",
                List.of(),
                List.of("PENDING", "CONFIRMED", "CANCELLED")
        );

        List<FactBatch.SymbolFact> facts = extractor.extractEnumFacts(model);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).symbolKind()).isEqualTo("ENUM");
        assertThat(facts.get(0).symbolFqn()).isEqualTo("io.orders.OrderStatus");
        assertThat(facts.get(0).attributes()).contains("PENDING");
    }

    @Test
    void extractMethodFacts_returnsEmpty_whenNoPublicMethods() {
        ParsedModel model = new ParsedModel(
                "Foo.java", "io.Foo", List.of(), List.of(), List.of(),
                List.of(), List.of(), false, null,
                null, List.of(), List.of()
        );
        assertThat(extractor.extractMethodFacts(model)).isEmpty();
        assertThat(extractor.extractEnumFacts(model)).isEmpty();
    }
}

package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JavaParserEndpointTest {

    private final JavaParserService parser = new JavaParserService();

    @Test
    void requestMapping_withExplicitPostMethod() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class HistoryController {
                    @RequestMapping(value = "/shopping/history",
                            produces = {"application/json"},
                            consumes = {"application/json"},
                            method = RequestMethod.POST)
                    public void getShoppingHistory() {}
                }
                """;
        ParsedModel model = parser.parse("HistoryController.java", source);
        assertThat(model.endpoints())
                .extracting(ParsedModel.EndpointDef::httpMethod, ParsedModel.EndpointDef::path)
                .containsExactly(tuple("POST", "/shopping/history"));
    }

    @Test
    void requestMapping_withMultipleMethods() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class DualController {
                    @RequestMapping(value = "/resource", method = {RequestMethod.GET, RequestMethod.POST})
                    public void handle() {}
                }
                """;
        ParsedModel model = parser.parse("DualController.java", source);
        assertThat(model.endpoints())
                .extracting(ParsedModel.EndpointDef::httpMethod, ParsedModel.EndpointDef::path)
                .containsExactlyInAnyOrder(
                        tuple("GET", "/resource"),
                        tuple("POST", "/resource"));
    }

    @Test
    void verbSpecificMappings() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class VerbsController {
                    @GetMapping("/a") public void a() {}
                    @PostMapping("/b") public void b() {}
                    @PutMapping("/c") public void c() {}
                    @DeleteMapping("/d") public void d() {}
                    @PatchMapping("/e") public void e() {}
                }
                """;
        ParsedModel model = parser.parse("VerbsController.java", source);
        assertThat(model.endpoints())
                .extracting(ParsedModel.EndpointDef::httpMethod, ParsedModel.EndpointDef::path)
                .containsExactlyInAnyOrder(
                        tuple("GET", "/a"),
                        tuple("POST", "/b"),
                        tuple("PUT", "/c"),
                        tuple("DELETE", "/d"),
                        tuple("PATCH", "/e"));
    }

    @Test
    void requestMapping_withoutMethod_emitsAllSupportedVerbs() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class HomeController {
                    @RequestMapping("/")
                    public String index() { return "ok"; }
                }
                """;
        ParsedModel model = parser.parse("HomeController.java", source);
        List<String> methods = model.endpoints().stream()
                .map(ParsedModel.EndpointDef::httpMethod)
                .distinct()
                .sorted()
                .toList();
        assertThat(methods).containsExactly("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");
        assertThat(model.endpoints()).allMatch(ep -> "/".equals(ep.path()));
    }

    @Test
    void classLevelPathPrefix_combinedWithMethodPost() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                public class ApiController {
                    @RequestMapping(value = "/items", method = RequestMethod.POST)
                    public void create() {}
                }
                """;
        ParsedModel model = parser.parse("ApiController.java", source);
        assertThat(model.endpoints())
                .extracting(ParsedModel.EndpointDef::httpMethod, ParsedModel.EndpointDef::path)
                .containsExactly(tuple("POST", "/api/items"));
    }
}

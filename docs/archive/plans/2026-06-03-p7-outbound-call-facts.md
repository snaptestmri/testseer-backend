# P7: Outbound Call Facts — Parser Upgrade + Query Endpoint

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `JavaParserService` to extract actual HTTP method + path from method bodies (RestClient, WebClient, RestTemplate, FeignClient), then expose the stored `outbound_call_facts` table via a new `GET /v1/facts/outbound` endpoint.

**Architecture:** Two independent parts. Part A (Tasks 1–2) upgrades the parser to populate `http_method` and `path` fields that are currently always `null`. It adds to existing field-level detection — both types of facts are valuable. Part B (Task 3) adds a read endpoint to `FactQueryController` following the exact same pattern as the existing `GET /v1/facts/class`.

**Tech Stack:** JavaParser 3.25, Spring Boot 3.3, JdbcClient, MockMvc (unit tests).

**Prerequisite:** P1–P6 complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   ├── ingestion/
│   │   └── JavaParserService.java          (modify: upgrade extractOutboundCalls)
│   └── query/
│       └── FactQueryController.java        (modify: add GET /v1/facts/outbound)
└── test/java/io/testseer/backend/
    ├── ingestion/
    │   └── JavaParserOutboundTest.java     (create: unit tests for parser upgrade)
    └── query/
        └── OutboundFactControllerTest.java (create: MockMvc tests for new endpoint)
```

**No schema changes required** — `outbound_call_facts` already has nullable `http_method` and `path` columns.

---

### Task 1: Parser upgrade — RestClient, WebClient, RestTemplate method-body traversal

**Files:**
- Modify: `src/main/java/io/testseer/backend/ingestion/JavaParserService.java`
- Create: `src/test/java/io/testseer/backend/ingestion/JavaParserOutboundTest.java`

Current state: `extractOutboundCalls()` walks class fields and emits one fact per HTTP client field — `httpMethod` and `path` are always `null`. After this task, it will also walk method bodies and emit specific facts for each detected call site.

- [ ] **Step 1: Write failing tests**

```java
package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserOutboundTest {

    private final JavaParserService parser = new JavaParserService();

    // -----------------------------------------------------------------------
    // RestClient / WebClient chain detection
    // -----------------------------------------------------------------------

    @Test
    void restClient_getUri_extractsGetWithPath() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestClient;
                public class PaymentClient {
                    private final RestClient restClient;
                    public PaymentClient(RestClient restClient) { this.restClient = restClient; }
                    public String fetch(String id) {
                        return restClient.get().uri("/payments/" + id).retrieve().body(String.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("PaymentClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("GET");
        assertThat(calls.get(0).path()).contains("/payments/");
        assertThat(calls.get(0).sourceMethod()).contains("fetch");
    }

    @Test
    void restClient_postUri_extractsPostWithPath() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestClient;
                public class OrderClient {
                    private final RestClient restClient;
                    public void create() {
                        restClient.post().uri("/orders").retrieve().toBodilessEntity();
                    }
                }
                """;

        ParsedModel model = parser.parse("OrderClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("POST");
        assertThat(calls.get(0).path()).isEqualTo("/orders");
    }

    // -----------------------------------------------------------------------
    // RestTemplate explicit method detection
    // -----------------------------------------------------------------------

    @Test
    void restTemplate_getForEntity_extractsGetWithPath() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestTemplate;
                public class InventoryClient {
                    private final RestTemplate restTemplate;
                    public void check() {
                        restTemplate.getForEntity("/inventory/items", String.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("InventoryClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("GET");
        assertThat(calls.get(0).path()).isEqualTo("/inventory/items");
        assertThat(calls.get(0).clientType()).isEqualTo("RestTemplate");
    }

    @Test
    void restTemplate_postForEntity_extractsPost() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestTemplate;
                public class ShipmentClient {
                    private final RestTemplate template;
                    public void ship() {
                        template.postForEntity("/shipments", null, Void.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("ShipmentClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("POST");
        assertThat(calls.get(0).path()).isEqualTo("/shipments");
    }

    @Test
    void noHttpCalls_returnsFieldLevelFallback() {
        // Class has a RestClient field but no detectable method-level calls
        String source = """
                package com.example;
                import org.springframework.web.client.RestClient;
                public class AmbiguousClient {
                    private final RestClient client;
                    public void doSomething(String uri) {
                        client.get().uri(uri).retrieve().body(String.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("AmbiguousClient.java", source);

        // uri is a variable, not a string literal — falls back to field-level fact
        assertThat(model.outboundCalls()).isNotEmpty();
        // At minimum the field-level fact is present
        assertThat(model.outboundCalls().stream()
                .anyMatch(c -> c.clientType().contains("RestClient"))).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=JavaParserOutboundTest -q 2>&1 | tail -15
```

Expected: Tests 1–3 FAIL (httpMethod is null in current impl). Test 4 may pass already.

- [ ] **Step 3: Upgrade `extractOutboundCalls()` in JavaParserService**

Replace the existing `extractOutboundCalls()` method entirely with:

```java
private List<ParsedModel.OutboundCallDef> extractOutboundCalls(
        ClassOrInterfaceDeclaration cls) {
    List<ParsedModel.OutboundCallDef> result = new ArrayList<>();

    // --- Method-body traversal: RestClient / WebClient ---
    // Pattern: <anything>.get().uri("path"), .post().uri("path"), etc.
    Map<String, String> HTTP_VERBS = Map.of(
            "get", "GET", "post", "POST", "put", "PUT",
            "delete", "DELETE", "patch", "PATCH"
    );

    cls.getMethods().forEach(method -> {
        method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {

            // RestClient / WebClient: .uri(...) chained on .get()/.post()/etc.
            if ("uri".equals(call.getNameAsString())
                    && call.getScope().isPresent()
                    && !call.getArguments().isEmpty()) {
                com.github.javaparser.ast.expr.Expression scope = call.getScope().get();
                if (scope instanceof com.github.javaparser.ast.expr.MethodCallExpr verbCall) {
                    String httpMethod = HTTP_VERBS.get(verbCall.getNameAsString());
                    if (httpMethod != null) {
                        String rawArg = call.getArgument(0).toString().replace("\"", "");
                        result.add(new ParsedModel.OutboundCallDef(
                                "RestClient", httpMethod, rawArg,
                                cls.getNameAsString() + "#" + method.getNameAsString()
                        ));
                    }
                }
            }

            // RestTemplate: getForEntity("/path", ...), postForEntity("/path", ...), etc.
            Map<String, String> TEMPLATE_METHODS = Map.of(
                    "getForEntity",  "GET",
                    "getForObject",  "GET",
                    "postForEntity", "POST",
                    "postForObject", "POST",
                    "delete",        "DELETE",
                    "patchForEntity","PATCH"
            );
            String tmVerb = TEMPLATE_METHODS.get(call.getNameAsString());
            if (tmVerb != null && !call.getArguments().isEmpty()) {
                String rawArg = call.getArgument(0).toString().replace("\"", "");
                result.add(new ParsedModel.OutboundCallDef(
                        "RestTemplate", tmVerb, rawArg,
                        cls.getNameAsString() + "#" + method.getNameAsString()
                ));
            }
        });
    });

    // --- Field-level fallback: always emit presence signal for each client field ---
    // Complements method-level facts. Captures cases where the URI is dynamic
    // (variable, not a literal) and cannot be statically extracted.
    List<String> CLIENTS = List.of("RestClient", "WebClient", "RestTemplate", "FeignClient");
    cls.getFields().forEach(field -> {
        String type = field.getElementType().asString();
        if (CLIENTS.stream().anyMatch(type::contains)) {
            result.add(new ParsedModel.OutboundCallDef(
                    type, null, null, cls.getNameAsString()
            ));
        }
    });

    return result;
}
```

Also add the required imports at the top of `JavaParserService.java`. The `MethodCallExpr` and `Expression` classes are already available through JavaParser — but use fully qualified names inline (as shown above) OR add imports:

```java
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.Map;
import java.util.Set;
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=JavaParserOutboundTest -q 2>&1 | tail -10
```

Expected: All 4 PASS.

- [ ] **Step 5: Confirm existing parser tests still pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="JavaParserOutboundTest,FactExtractorTest,PeripheralDetectorTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/ingestion/JavaParserService.java \
        src/test/java/io/testseer/backend/ingestion/JavaParserOutboundTest.java
git commit -m "feat: upgrade JavaParserService to extract http_method and path from RestClient/RestTemplate call chains"
```

---

### Task 2: Parser upgrade — FeignClient interface detection

**Files:**
- Modify: `src/main/java/io/testseer/backend/ingestion/JavaParserService.java`
- Modify: `src/test/java/io/testseer/backend/ingestion/JavaParserOutboundTest.java`

FeignClient interfaces look like controllers — they have `@FeignClient` on the class and `@GetMapping`/`@PostMapping` etc. on methods. The existing `extractEndpoints()` logic already handles mapping annotations. Reuse it.

- [ ] **Step 1: Write failing FeignClient test — append to `JavaParserOutboundTest`**

Add this test to the existing `JavaParserOutboundTest` class:

```java
    // -----------------------------------------------------------------------
    // FeignClient interface detection
    // -----------------------------------------------------------------------

    @Test
    void feignClient_extractsMethodAnnotationsAsOutboundCalls() {
        String source = """
                package com.example;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                @FeignClient(name = "payment-service")
                public interface PaymentServiceClient {
                    @GetMapping("/payments/{id}")
                    String getPayment(String id);
                    @PostMapping("/payments")
                    String createPayment(String body);
                }
                """;

        ParsedModel model = parser.parse("PaymentServiceClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(2);
        assertThat(calls).anyMatch(c -> "GET".equals(c.httpMethod()) && "/payments/{id}".equals(c.path()));
        assertThat(calls).anyMatch(c -> "POST".equals(c.httpMethod()) && "/payments".equals(c.path()));
        assertThat(calls).allMatch(c -> "FeignClient".equals(c.clientType()));
    }
```

- [ ] **Step 2: Run the new test to verify it fails**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="JavaParserOutboundTest#feignClient_extractsMethodAnnotationsAsOutboundCalls" -q 2>&1 | tail -10
```

Expected: FAIL.

- [ ] **Step 3: Add FeignClient detection to `JavaParserService.parse()`**

In `parse()`, after extracting `endpoints` and `outboundCalls`, check if the class is a FeignClient interface and, if so, convert its endpoints to outbound calls. Update the return statement section:

```java
        List<ParsedModel.EndpointDef> endpoints = extractEndpoints(cls);
        List<ParsedModel.OutboundCallDef> outboundCalls = extractOutboundCalls(cls);

        // FeignClient: treat this interface's own mapping annotations as outbound call defs.
        // The consuming service calls these — so from the consumer's perspective they are
        // outbound facts. From the FeignClient interface file itself, we record what it declares.
        boolean isFeignClient = annotations.contains("FeignClient");
        if (isFeignClient) {
            List<ParsedModel.OutboundCallDef> feignCalls = endpoints.stream()
                    .map(ep -> new ParsedModel.OutboundCallDef(
                            "FeignClient", ep.httpMethod(), ep.path(),
                            classFqn + "#" + ep.methodName()
                    ))
                    .toList();
            // Merge with any existing outbound calls
            List<ParsedModel.OutboundCallDef> merged = new ArrayList<>(outboundCalls);
            merged.addAll(feignCalls);
            outboundCalls = merged;
        }

        return new ParsedModel(
                filePath, classFqn, annotations, constructorParams,
                fieldInjections, endpoints, outboundCalls, false, null
        );
```

Note: `outboundCalls` must be declared as a local variable (not `final`) at this point. If the existing `List<ParsedModel.OutboundCallDef> outboundCalls = extractOutboundCalls(cls);` line uses `var`, change it to `List<ParsedModel.OutboundCallDef>` explicitly.

Also add `import java.util.ArrayList;` if not already present (it is — it's used in `extractEndpoints`).

- [ ] **Step 4: Run all outbound tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=JavaParserOutboundTest -q 2>&1 | tail -10
```

Expected: All 5 PASS.

- [ ] **Step 5: Run full non-Docker suite to confirm no regressions**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="JavaParserOutboundTest,FactExtractorTest,PeripheralDetectorTest,GraphQueryControllerTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/ingestion/JavaParserService.java \
        src/test/java/io/testseer/backend/ingestion/JavaParserOutboundTest.java
git commit -m "feat: detect FeignClient interface annotations as outbound call facts"
```

---

### Task 3: `GET /v1/facts/outbound` query endpoint

**Files:**
- Modify: `src/main/java/io/testseer/backend/query/FactQueryController.java`
- Create: `src/test/java/io/testseer/backend/query/OutboundFactControllerTest.java`

Follows the exact same pattern as `GET /v1/facts/class`. Optional `sourceSymbol` param to filter by caller class.

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.query;

import io.testseer.backend.graph.GraphProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({FactQueryController.class, GraphQueryController.class})
class OutboundFactControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JdbcClient jdbcClient;
    @MockBean GraphProjectionService graphService;
    @MockBean FreshnessResolver freshnessResolver;
    @MockBean CacheService cacheService;

    @Test
    void outbound_returns200_withOutboundCallList() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(cacheService.get(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> supplier = inv.getArgument(5);
                    return supplier.get();
                });
        when(jdbcClient.sql(anyString())).thenReturn(
                org.mockito.Mockito.mock(
                        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class,
                        org.mockito.Answers.RETURNS_DEEP_STUBS
                )
        );

        mockMvc.perform(get("/v1/facts/outbound")
                        .param("serviceId", "svc-001")
                        .param("orgId", "acme")
                        .param("repo", "repo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));
    }

    @Test
    void outbound_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/facts/outbound")
                        .param("serviceId", "svc-missing")
                        .param("orgId", "acme")
                        .param("repo", "repo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void outbound_returns202_whenIndexing() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.INDEXING);

        mockMvc.perform(get("/v1/facts/outbound")
                        .param("serviceId", "svc-001")
                        .param("orgId", "acme")
                        .param("repo", "repo"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.freshnessStatus").value("INDEXING"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=OutboundFactControllerTest -q 2>&1 | tail -15
```

Expected: All 3 FAIL — `/v1/facts/outbound` does not exist yet.

- [ ] **Step 3: Add `GET /v1/facts/outbound` to `FactQueryController`**

Add the `OutboundCallView` record to `FactQueryController`:

```java
    public record OutboundCallView(
            String sourceSymbol,
            String httpMethod,       // null when only client presence detected
            String path,             // null when only client presence detected
            String evidenceSource,
            double confidence,
            Instant indexedAt
    ) {}
```

Add the new endpoint method:

```java
    @GetMapping("/outbound")
    public ResponseEntity<ResponseEnvelope<List<OutboundCallView>>> getOutboundFacts(
            @RequestParam String serviceId,
            @RequestParam(defaultValue = "acme") String orgId,
            @RequestParam(defaultValue = "") String repo,
            @RequestParam(required = false) String sourceSymbol) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }

        String cacheKey = sourceSymbol != null ? sourceSymbol : "__all__";

        @SuppressWarnings("unchecked")
        List<OutboundCallView> facts = cache.get(orgId, repo, serviceId,
                "facts:outbound", cacheKey,
                () -> queryOutboundFacts(serviceId, sourceSymbol),
                (Class<List<OutboundCallView>>) (Class<?>) List.class);

        RunMeta run = latestRun(serviceId);
        var envelope = ResponseEnvelope.of(run.indexedAt(), run.commitSha(), status, facts);
        int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
        return ResponseEntity.status(httpStatus).body(envelope);
    }

    private List<OutboundCallView> queryOutboundFacts(String serviceId, String sourceSymbol) {
        String sql = """
                SELECT source_symbol, http_method, path, evidence_source, confidence, indexed_at
                FROM outbound_call_facts
                WHERE service_id = :svcId
                """ + (sourceSymbol != null ? "  AND source_symbol = :srcSym\n" : "") + """
                ORDER BY source_symbol, http_method NULLS LAST, path NULLS LAST
                """;

        var spec = db.sql(sql).param("svcId", serviceId);
        if (sourceSymbol != null) spec = spec.param("srcSym", sourceSymbol);

        return spec.query((rs, row) -> new OutboundCallView(
                rs.getString("source_symbol"),
                rs.getString("http_method"),
                rs.getString("path"),
                rs.getString("evidence_source"),
                rs.getDouble("confidence"),
                rs.getTimestamp("indexed_at").toInstant()
        )).list();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=OutboundFactControllerTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 5: Run full non-Docker suite**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="JavaParserOutboundTest,FactExtractorTest,PeripheralDetectorTest,GraphQueryControllerTest,OutboundFactControllerTest" -q 2>&1 | tail -10
```

Expected: All pass (17 tests total).

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/query/FactQueryController.java \
        src/test/java/io/testseer/backend/query/OutboundFactControllerTest.java
git commit -m "feat: add GET /v1/facts/outbound endpoint exposing outbound_call_facts with optional sourceSymbol filter"
```

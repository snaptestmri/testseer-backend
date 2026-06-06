# P13: Parser Semantic Enrichment

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `JavaParserService` to extract business-level semantics already encoded in the code: class/method Javadoc, public method signatures (return types, parameter types, thrown exceptions), and enum values. Emit these as `METHOD` and `ENUM` symbol facts alongside the existing `CLASS` and `ENDPOINT` facts. Then add an LLM synthesis step that reads the enriched facts per service and generates a plain-English business description, stored in `service_registry.metadata`. This description powers the Cursor agent's understanding of what each service does.

**Architecture:** Four layers. `ParsedModel` gains three new fields (`classJavadoc`, `publicMethods`, `enumValues`). `JavaParserService` populates them from JavaParser's AST. `FactExtractor` emits `METHOD` and `ENUM` symbol facts from the new fields. `ServiceDescriptionService` calls the Claude API with the enriched facts and stores the result in `service_registry.metadata`.

**No schema migration required** — `symbol_facts.symbol_kind` is unconstrained VARCHAR; `service_registry.metadata` is already JSONB.

**Tech Stack:** JavaParser 3.25, Spring Boot 3.3, Anthropic Java SDK (Task 4 only), JdbcClient.

**Prerequisite:** P1–P12 complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   ├── ingestion/
│   │   ├── ParsedModel.java              (modify: add classJavadoc, publicMethods, enumValues)
│   │   ├── JavaParserService.java        (modify: extract Javadoc, methods, enums)
│   │   └── FactExtractor.java           (modify: emit METHOD and ENUM facts)
│   └── analysis/
│       ├── ServiceDescriptionService.java  (new)
│       └── ServiceDescriptionController.java (new: GET /v1/services/{serviceId}/description)
└── test/java/io/testseer/backend/
    └── ingestion/
        └── JavaParserSemanticTest.java   (new: unit tests for enriched parsing)
```

**Existing files that call `new ParsedModel(...)` — all need updating:**
- `src/main/java/io/testseer/backend/ingestion/JavaParserService.java` (4 sites)
- `src/test/java/io/testseer/backend/ingestion/FactExtractorTest.java` (3 sites)
- `src/test/java/io/testseer/backend/ingestion/PeripheralDetectorTest.java` (3 sites)
- `src/test/java/io/testseer/backend/ingestion/DualWriteServiceTest.java` (1 site)
- `src/test/java/io/testseer/backend/admin/LocalIndexTriggerServiceTest.java` (1 site)

---

### Task 1: Extend `ParsedModel` — add semantic fields + update all callsites

**Files:**
- Modify: `src/main/java/io/testseer/backend/ingestion/ParsedModel.java`
- Modify: all 5 files listed above (pass `null`/`List.of()` for new fields at existing callsites)

- [ ] **Step 1: Update `ParsedModel.java`**

Replace the existing record entirely:

```java
package io.testseer.backend.ingestion;

import java.util.List;

public record ParsedModel(
        String filePath,
        String classFqn,
        List<String> annotations,
        List<String> constructorParamTypes,
        List<String> fieldInjectionTypes,
        List<EndpointDef> endpoints,
        List<OutboundCallDef> outboundCalls,
        boolean parseError,
        String parseErrorDetail,
        // Semantic enrichment — null/empty when not available
        String classJavadoc,
        List<MethodDef> publicMethods,
        List<String> enumValues
) {
    public record EndpointDef(String httpMethod, String path, String methodName) {}

    public record OutboundCallDef(String clientType, String httpMethod, String path,
                                   String sourceMethod) {}

    public record MethodDef(
            String name,
            String javadoc,         // null when no Javadoc present
            String returnType,
            List<String> parameterTypes,
            List<String> thrownExceptions
    ) {}
}
```

- [ ] **Step 2: Update all existing `new ParsedModel(...)` callsites**

At every existing callsite, append three trailing arguments: `null, List.of(), List.of()` for the new fields.

For example, the error-case return in `JavaParserService`:
```java
// BEFORE:
return new ParsedModel(
        filePath, null, List.of(), List.of(), List.of(),
        List.of(), List.of(), true, detail
);
// AFTER:
return new ParsedModel(
        filePath, null, List.of(), List.of(), List.of(),
        List.of(), List.of(), true, detail,
        null, List.of(), List.of()
);
```

Apply to every `new ParsedModel(...)` call across all 5 affected files. The three new args are always `null, List.of(), List.of()` at existing callsites — the actual population happens in Task 2.

- [ ] **Step 3: Compile to verify all callsites are updated**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. If it fails, find remaining callsites with:
```bash
grep -rn "new ParsedModel(" src/ | grep -v "\.class"
```

- [ ] **Step 4: Run existing tests to confirm no regressions**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="FactExtractorTest,PeripheralDetectorTest,JavaParserOutboundTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 5: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/ingestion/ParsedModel.java \
        src/main/java/io/testseer/backend/ingestion/JavaParserService.java \
        src/test/java/io/testseer/backend/ingestion/ \
        src/test/java/io/testseer/backend/admin/LocalIndexTriggerServiceTest.java
git commit -m "refactor: extend ParsedModel with classJavadoc, publicMethods, enumValues fields"
```

---

### Task 2: Extend `JavaParserService` to extract semantic data

**Files:**
- Modify: `src/main/java/io/testseer/backend/ingestion/JavaParserService.java`
- Create: `src/test/java/io/testseer/backend/ingestion/JavaParserSemanticTest.java`

- [ ] **Step 1: Write failing semantic extraction tests**

Create `src/test/java/io/testseer/backend/ingestion/JavaParserSemanticTest.java`:

```java
package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserSemanticTest {

    private final JavaParserService parser = new JavaParserService();

    @Test
    void extractsClassJavadoc() {
        String source = """
                package com.example;
                /**
                 * Handles order creation and lifecycle management.
                 * Coordinates with PaymentService to charge customers.
                 */
                public class OrderService {
                    public void createOrder() {}
                }
                """;

        ParsedModel model = parser.parse("OrderService.java", source);

        assertThat(model.classJavadoc()).contains("order creation");
        assertThat(model.classJavadoc()).contains("PaymentService");
    }

    @Test
    void extractsPublicMethodsWithJavadocAndSignature() {
        String source = """
                package com.example;
                public class RefundProcessor {
                    /**
                     * Processes a full refund. Only valid within 30 days of delivery.
                     */
                    public RefundResult processRefund(String orderId, Money amount)
                            throws RefundWindowExpiredException, PaymentException {
                        return null;
                    }
                    private void internalHelper() {}
                    public String getId() { return null; }  // getter — excluded
                }
                """;

        ParsedModel model = parser.parse("RefundProcessor.java", source);

        List<ParsedModel.MethodDef> methods = model.publicMethods();
        assertThat(methods).hasSize(1); // only processRefund — getId is a getter
        ParsedModel.MethodDef m = methods.get(0);
        assertThat(m.name()).isEqualTo("processRefund");
        assertThat(m.javadoc()).contains("30 days");
        assertThat(m.returnType()).isEqualTo("RefundResult");
        assertThat(m.parameterTypes()).containsExactly("String", "Money");
        assertThat(m.thrownExceptions()).containsExactlyInAnyOrder(
                "RefundWindowExpiredException", "PaymentException");
    }

    @Test
    void excludesGettersSettersFromPublicMethods() {
        String source = """
                package com.example;
                public class Order {
                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                    public boolean isValid() { return true; }
                    public void cancel(String reason) {}  // NOT a getter/setter
                    private String id;
                }
                """;

        ParsedModel model = parser.parse("Order.java", source);

        assertThat(model.publicMethods().stream().map(ParsedModel.MethodDef::name))
                .containsExactly("cancel");
    }

    @Test
    void extractsEnumValues() {
        String source = """
                package com.example;
                /**
                 * Represents the lifecycle states of an order.
                 */
                public enum OrderStatus {
                    PENDING, PAYMENT_PROCESSING, CONFIRMED,
                    SHIPPED, DELIVERED, CANCELLED, REFUNDED
                }
                """;

        ParsedModel model = parser.parse("OrderStatus.java", source);

        assertThat(model.enumValues()).containsExactlyInAnyOrder(
                "PENDING", "PAYMENT_PROCESSING", "CONFIRMED",
                "SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED");
        assertThat(model.classJavadoc()).contains("lifecycle states");
    }

    @Test
    void publicMethodsIsEmpty_whenNoJavadocAndNoExceptions() {
        // Method with no Javadoc is still extracted — Javadoc field is just null
        String source = """
                package com.example;
                public class SimpleService {
                    public void execute(String input) {}
                }
                """;

        ParsedModel model = parser.parse("SimpleService.java", source);

        assertThat(model.publicMethods()).hasSize(1);
        assertThat(model.publicMethods().get(0).javadoc()).isNull();
        assertThat(model.publicMethods().get(0).thrownExceptions()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=JavaParserSemanticTest -q 2>&1 | tail -10
```

Expected: All 5 FAIL — semantic fields are empty.

- [ ] **Step 3: Extend `JavaParserService` — add Javadoc, method, and enum extraction**

Add these imports to `JavaParserService.java`:

```java
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
```

Add these private constants (alongside `HTTP_VERBS` etc.):

```java
private static final java.util.Set<String> GETTER_SETTER_PREFIXES =
        java.util.Set.of("get", "set", "is");
```

Add this private helper method:

```java
private static boolean isGetterOrSetter(MethodDeclaration method) {
    String name = method.getNameAsString();
    return GETTER_SETTER_PREFIXES.stream().anyMatch(name::startsWith)
            && method.getParameters().size() <= 1;
}

private static String extractJavadoc(
        com.github.javaparser.ast.nodeTypes.NodeWithJavadoc<?> node) {
    return node.getJavadocComment()
            .map(jd -> jd.parse().getDescription().toText().trim())
            .filter(s -> !s.isBlank())
            .orElse(null);
}

private List<ParsedModel.MethodDef> extractPublicMethods(
        ClassOrInterfaceDeclaration cls) {
    return cls.getMethods().stream()
            .filter(MethodDeclaration::isPublic)
            .filter(m -> !isGetterOrSetter(m))
            .map(m -> new ParsedModel.MethodDef(
                    m.getNameAsString(),
                    extractJavadoc(m),
                    m.getType().asString(),
                    m.getParameters().stream()
                            .map(p -> p.getType().asString())
                            .toList(),
                    m.getThrownExceptions().stream()
                            .map(e -> e.asString())
                            .toList()
            ))
            .toList();
}
```

**Update `parse()` method** — after the existing block that processes `ClassOrInterfaceDeclaration`, add enum support and populate new fields.

Replace the section that builds the final return value:

```java
        // After existing endpoint and outbound extraction:
        List<ParsedModel.EndpointDef> endpoints    = extractEndpoints(cls);
        List<ParsedModel.OutboundCallDef> outboundCalls = extractOutboundCalls(cls);

        // NEW: FeignClient handling (existing code)
        boolean isFeignClient = annotations.contains("FeignClient");
        if (isFeignClient) {
            // ... existing code unchanged ...
        }

        // NEW: semantic extraction
        String classJavadoc  = extractJavadoc(cls);
        List<ParsedModel.MethodDef> publicMethods = extractPublicMethods(cls);

        return new ParsedModel(
                filePath, classFqn, annotations, constructorParams,
                fieldInjections, endpoints, outboundCalls, false, null,
                classJavadoc, publicMethods, List.of()  // enumValues empty for classes
        );
```

**Add enum handling** — in `parse()`, after the `primaryClass.isEmpty()` check, add enum detection before returning the empty model:

```java
        // If not a class/interface, check for enum
        Optional<EnumDeclaration> enumDecl = cu.getTypes().stream()
                .filter(t -> t instanceof EnumDeclaration)
                .map(t -> (EnumDeclaration) t)
                .findFirst();

        if (enumDecl.isPresent()) {
            EnumDeclaration en = enumDecl.get();
            String enumFqn = cu.getPackageDeclaration()
                    .map(p -> p.getNameAsString() + "." + en.getNameAsString())
                    .orElse(en.getNameAsString());
            List<String> enumValues = en.getEntries().stream()
                    .map(e -> e.getNameAsString())
                    .toList();
            List<String> enumAnnotations = en.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .toList();
            return new ParsedModel(
                    filePath, enumFqn, enumAnnotations, List.of(), List.of(),
                    List.of(), List.of(), false, null,
                    extractJavadoc(en), List.of(), enumValues
            );
        }

        // Not a class or enum — return empty model
        return new ParsedModel(filePath, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), false, null,
                null, List.of(), List.of());
```

**Important:** The enum check must go BEFORE the existing `primaryClass.isEmpty()` early return that returns an empty model. Restructure the control flow so enum handling is attempted first.

- [ ] **Step 4: Run semantic tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=JavaParserSemanticTest -q 2>&1 | tail -10
```

Expected: All 5 PASS.

- [ ] **Step 5: Run full non-Docker suite to confirm no regressions**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="JavaParserSemanticTest,JavaParserOutboundTest,FactExtractorTest,PeripheralDetectorTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/ingestion/JavaParserService.java \
        src/test/java/io/testseer/backend/ingestion/JavaParserSemanticTest.java
git commit -m "feat: extract class Javadoc, public method signatures, thrown exceptions, and enum values from Java source"
```

---

### Task 3: Extend `FactExtractor` to emit METHOD and ENUM facts

**Files:**
- Modify: `src/main/java/io/testseer/backend/ingestion/FactExtractor.java`
- Modify: `src/test/java/io/testseer/backend/ingestion/FactExtractorTest.java`

`symbol_facts.symbol_kind` is VARCHAR with no check constraint — no migration needed.

- [ ] **Step 1: Add new fact extraction methods to `FactExtractor`**

Add these two new public methods alongside the existing ones:

```java
    public List<FactBatch.SymbolFact> extractMethodFacts(ParsedModel model) {
        if (model.classFqn() == null || model.publicMethods().isEmpty()) return List.of();

        return model.publicMethods().stream()
                .map(m -> {
                    String fqn = model.classFqn() + "#" + m.name();
                    String attrs = toJson(java.util.Map.of(
                            "returnType",       m.returnType(),
                            "parameterTypes",   m.parameterTypes(),
                            "thrownExceptions", m.thrownExceptions(),
                            "javadoc",          m.javadoc() != null ? m.javadoc() : ""
                    ));
                    return new FactBatch.SymbolFact(
                            model.filePath(), fqn, "METHOD",
                            attrs, "javaparser", 1.0
                    );
                })
                .toList();
    }

    public List<FactBatch.SymbolFact> extractEnumFacts(ParsedModel model) {
        if (model.classFqn() == null || model.enumValues().isEmpty()) return List.of();

        String attrs = toJson(java.util.Map.of(
                "enumValues", model.enumValues(),
                "javadoc",    model.classJavadoc() != null ? model.classJavadoc() : ""
        ));
        return List.of(new FactBatch.SymbolFact(
                model.filePath(), model.classFqn(), "ENUM",
                attrs, "javaparser", 1.0
        ));
    }
```

- [ ] **Step 2: Add tests for the new extractors**

Add to the existing `FactExtractorTest.java`:

```java
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
        FactBatch.SymbolFact fact = facts.get(0);
        assertThat(fact.symbolFqn()).isEqualTo("io.orders.OrderService#createOrder");
        assertThat(fact.symbolKind()).isEqualTo("METHOD");
        assertThat(fact.attributes()).contains("PaymentException");
        assertThat(fact.attributes()).contains("createOrder");
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
        assertThat(facts.get(0).attributes()).contains("PENDING");
        assertThat(facts.get(0).attributes()).contains("CANCELLED");
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
```

- [ ] **Step 3: Run tests**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=FactExtractorTest -q 2>&1 | tail -10
```

Expected: All pass (new tests + existing 4).

- [ ] **Step 4: Wire new extractors into `WorkerPipeline`**

In `WorkerPipeline.java`, in the `process()` method, add METHOD and ENUM fact extraction alongside the existing symbol facts. Find the block that builds `symbolFacts` and extend it:

```java
            List<FactBatch.SymbolFact> symbolFacts = models.stream()
                    .flatMap(m -> factExtractor.extractSymbolFacts(m).stream())
                    .toList();
            // NEW: method and enum facts appended to symbol facts list
            List<FactBatch.SymbolFact> methodFacts = models.stream()
                    .flatMap(m -> factExtractor.extractMethodFacts(m).stream())
                    .toList();
            List<FactBatch.SymbolFact> enumFacts = models.stream()
                    .flatMap(m -> factExtractor.extractEnumFacts(m).stream())
                    .toList();
            List<FactBatch.SymbolFact> allSymbolFacts = new java.util.ArrayList<>(symbolFacts);
            allSymbolFacts.addAll(methodFacts);
            allSymbolFacts.addAll(enumFacts);
```

Then change the `FactBatch` construction to use `allSymbolFacts`:
```java
            FactBatch batch = new FactBatch(
                    job.jobId(), job.orgId(), job.repo(), job.serviceId(),
                    job.commitSha(), snapshotType,
                    allSymbolFacts, outboundFacts, peripheralFacts, unsupported
            );
```

Also do the same in `LocalIndexTriggerService.java` — same pattern, same update.

- [ ] **Step 5: Compile full project**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn compile -q 2>&1 | tail -5
```

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/ingestion/FactExtractor.java \
        src/main/java/io/testseer/backend/ingestion/WorkerPipeline.java \
        src/main/java/io/testseer/backend/admin/LocalIndexTriggerService.java \
        src/test/java/io/testseer/backend/ingestion/FactExtractorTest.java
git commit -m "feat: emit METHOD and ENUM symbol facts from enriched ParsedModel; wire into WorkerPipeline and LocalIndexTriggerService"
```

---

### Task 4: `ServiceDescriptionService` — LLM synthesis via Claude API

**Files:**
- Modify: `pom.xml` — add Anthropic SDK dependency
- Modify: `src/main/resources/application.yml` — add `testseer.anthropic.api-key` property
- Create: `src/main/java/io/testseer/backend/analysis/ServiceDescriptionService.java`
- Create: `src/main/java/io/testseer/backend/analysis/ServiceDescriptionController.java`
- Create: `src/test/java/io/testseer/backend/analysis/ServiceDescriptionControllerTest.java`

- [ ] **Step 1: Add Anthropic SDK to `pom.xml`**

Add inside `<dependencies>`:
```xml
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>0.8.0</version>
</dependency>
```

Check [Maven Central](https://central.sonatype.com/artifact/com.anthropic/anthropic-java) for the latest version before committing.

- [ ] **Step 2: Add config to `application.yml`**

```yaml
testseer:
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
    enabled: ${ANTHROPIC_ENABLED:false}
```

- [ ] **Step 3: Create `ServiceDescriptionService.java`**

```java
package io.testseer.backend.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "testseer.anthropic.enabled", havingValue = "true")
public class ServiceDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(ServiceDescriptionService.class);

    private final JdbcClient db;
    private final AnthropicClient anthropic;

    public ServiceDescriptionService(
            JdbcClient db,
            @Value("${testseer.anthropic.api-key:}") String apiKey) {
        this.db        = db;
        this.anthropic = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    /**
     * Generates a plain-English business description for a service by sending
     * its indexed CLASS, METHOD, and ENUM facts to Claude. Stores the result
     * in service_registry.metadata->>'description'.
     */
    public String generateAndStore(String serviceId) {
        String description = generate(serviceId);
        db.sql("""
                UPDATE service_registry
                SET metadata = COALESCE(metadata, '{}')::jsonb
                           || jsonb_build_object('description', :desc,
                                                  'descriptionGeneratedAt', now()::text)
                WHERE service_id = :id
                """)
                .param("desc", description)
                .param("id",   serviceId)
                .update();
        log.info("Stored description for service {}", serviceId);
        return description;
    }

    public String getStored(String serviceId) {
        return db.sql("""
                SELECT metadata->>'description'
                FROM service_registry
                WHERE service_id = :id
                """)
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String generate(String serviceId) {
        // Gather enriched facts for this service (latest complete run)
        String latestSha = db.sql("""
                SELECT commit_sha FROM analysis_runs
                WHERE service_id = :id AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);

        if (latestSha == null) {
            return "Service has not been indexed yet.";
        }

        // CLASS facts with Javadoc
        String classSummary = db.sql("""
                SELECT symbol_fqn, attributes->>'javadoc' as javadoc
                FROM symbol_facts
                WHERE service_id = :id AND commit_sha = :sha AND symbol_kind = 'CLASS'
                ORDER BY symbol_fqn LIMIT 20
                """)
                .param("id",  serviceId)
                .param("sha", latestSha)
                .query((rs, row) -> rs.getString("symbol_fqn") +
                        (rs.getString("javadoc") != null ? ": " + rs.getString("javadoc") : ""))
                .list()
                .stream().collect(Collectors.joining("\n"));

        // METHOD facts with Javadoc and thrown exceptions
        String methodSummary = db.sql("""
                SELECT symbol_fqn, attributes->>'javadoc' as javadoc,
                       attributes->>'thrownExceptions' as exceptions
                FROM symbol_facts
                WHERE service_id = :id AND commit_sha = :sha AND symbol_kind = 'METHOD'
                  AND (attributes->>'javadoc' IS NOT NULL
                       OR attributes->>'thrownExceptions' != '[]')
                ORDER BY symbol_fqn LIMIT 30
                """)
                .param("id",  serviceId)
                .param("sha", latestSha)
                .query((rs, row) -> {
                    String fqn = rs.getString("symbol_fqn");
                    String doc = rs.getString("javadoc");
                    String exc = rs.getString("exceptions");
                    return fqn + (doc != null ? " — " + doc : "") +
                           (exc != null && !"[]".equals(exc) ? " [throws: " + exc + "]" : "");
                })
                .list()
                .stream().collect(Collectors.joining("\n"));

        // ENUM facts
        String enumSummary = db.sql("""
                SELECT symbol_fqn, attributes->>'enumValues' as values,
                       attributes->>'javadoc' as javadoc
                FROM symbol_facts
                WHERE service_id = :id AND commit_sha = :sha AND symbol_kind = 'ENUM'
                ORDER BY symbol_fqn LIMIT 10
                """)
                .param("id",  serviceId)
                .param("sha", latestSha)
                .query((rs, row) -> rs.getString("symbol_fqn") +
                        " values: " + rs.getString("values") +
                        (rs.getString("javadoc") != null ? " — " + rs.getString("javadoc") : ""))
                .list()
                .stream().collect(Collectors.joining("\n"));

        String prompt = """
                You are analysing a Java microservice to produce a concise business description.
                
                Classes and their documentation:
                %s
                
                Key methods with business rules:
                %s
                
                State machines (enums):
                %s
                
                In 3-5 sentences, describe:
                1. What business capability this service provides
                2. The main operations it supports
                3. Notable business rules inferred from exception types and Javadoc
                
                Write in plain English for a technical product manager. No bullet points.
                """.formatted(
                classSummary.isBlank() ? "(none indexed)" : classSummary,
                methodSummary.isBlank() ? "(none indexed)" : methodSummary,
                enumSummary.isBlank() ? "(none indexed)" : enumSummary
        );

        var message = anthropic.messages().create(
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_HAIKU_4_5_20251001)
                        .maxTokens(400)
                        .addUserMessage(prompt)
                        .build()
        );

        return message.content().stream()
                .filter(b -> b.isText())
                .map(b -> b.asText().text())
                .findFirst()
                .orElse("Could not generate description.");
    }
}
```

**Note on Anthropic SDK API:** The exact method names on `MessageCreateParams.Builder` and `ContentBlock` may differ slightly from the above. Read the SDK Javadoc or check the anthropic-java GitHub repo for the correct API before implementing. The pattern above reflects the Java SDK as of mid-2025.

- [ ] **Step 4: Create `ServiceDescriptionController.java`**

```java
package io.testseer.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Tag(name = "Analysis", description = "Impact analysis and test planning based on the indexed knowledge graph")
@RestController
@RequestMapping("/v1/services")
public class ServiceDescriptionController {

    private final Optional<ServiceDescriptionService> descriptionService;

    public ServiceDescriptionController(
            Optional<ServiceDescriptionService> descriptionService) {
        this.descriptionService = descriptionService;
    }

    @Operation(
        summary = "Get business description for a service",
        description = """
            Returns a plain-English description of what the service does, \
            generated by an LLM from the indexed class names, Javadoc, \
            method signatures, and enum values. Requires ANTHROPIC_ENABLED=true \
            and a valid ANTHROPIC_API_KEY. \
            POST to the same path to regenerate the description.""")
    @GetMapping("/{serviceId}/description")
    public ResponseEntity<String> getDescription(@PathVariable String serviceId) {
        if (descriptionService.isEmpty()) {
            return ResponseEntity.status(503)
                    .body("LLM description generation is disabled. Set ANTHROPIC_ENABLED=true.");
        }
        String stored = descriptionService.get().getStored(serviceId);
        if (stored == null) {
            return ResponseEntity.status(404)
                    .body("No description generated yet. POST to this endpoint to generate.");
        }
        return ResponseEntity.ok(stored);
    }

    @PostMapping("/{serviceId}/description")
    public ResponseEntity<String> generateDescription(@PathVariable String serviceId) {
        if (descriptionService.isEmpty()) {
            return ResponseEntity.status(503)
                    .body("LLM description generation is disabled. Set ANTHROPIC_ENABLED=true.");
        }
        String description = descriptionService.get().generateAndStore(serviceId);
        return ResponseEntity.ok(description);
    }
}
```

- [ ] **Step 5: Write MockMvc tests**

Create `src/test/java/io/testseer/backend/analysis/ServiceDescriptionControllerTest.java`:

```java
package io.testseer.backend.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceDescriptionController.class)
class ServiceDescriptionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean(name = "descriptionService")
    Optional<ServiceDescriptionService> descriptionService;

    @Test
    void getDescription_returns503_whenServiceDisabled() throws Exception {
        when(descriptionService.isEmpty()).thenReturn(true);

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void getDescription_returns404_whenNotGenerated() throws Exception {
        ServiceDescriptionService svc = org.mockito.Mockito.mock(ServiceDescriptionService.class);
        when(descriptionService.isEmpty()).thenReturn(false);
        when(descriptionService.get()).thenReturn(svc);
        when(svc.getStored("svc-001")).thenReturn(null);

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDescription_returns200_withStoredDescription() throws Exception {
        ServiceDescriptionService svc = org.mockito.Mockito.mock(ServiceDescriptionService.class);
        when(descriptionService.isEmpty()).thenReturn(false);
        when(descriptionService.get()).thenReturn(svc);
        when(svc.getStored("svc-001")).thenReturn("Manages order lifecycle including payment and fulfillment.");

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("order lifecycle")));
    }
}
```

- [ ] **Step 6: Run tests**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="ServiceDescriptionControllerTest,FactExtractorTest,JavaParserSemanticTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 7: Compile full project**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS. If Anthropic SDK classes are missing, verify the Maven coordinates and version from Maven Central.

- [ ] **Step 8: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add pom.xml \
        src/main/resources/application.yml \
        src/main/java/io/testseer/backend/analysis/ServiceDescriptionService.java \
        src/main/java/io/testseer/backend/analysis/ServiceDescriptionController.java \
        src/test/java/io/testseer/backend/analysis/ServiceDescriptionControllerTest.java
git commit -m "feat: add LLM-based service description generation from indexed Javadoc, methods, and enums via Claude API"
```

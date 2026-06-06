# P4: Analysis Workers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Kafka consumer pipeline that fetches Java source files from GitHub, parses them with JavaParser, extracts endpoint/dependency/outbound-call facts, classifies peripherals into Tier 1/2/3, and atomically dual-writes structured facts to Postgres and raw `ParsedModel` documents to MongoDB — committing the Kafka offset only after both writes succeed.

**Architecture:** Two consumers (`PrWorkerConsumer`, `BatchWorkerConsumer`) share a common processing pipeline. `GitHubSourceFetcher` retrieves `.java` files. `JavaParserService` parses them. `FactExtractor` derives typed facts. `PeripheralDetector` applies signal rules. `DualWriteService` orchestrates the atomic write and Kafka offset commit. Unsupported constructs produce an `UnsupportedConstructFact` and never silently omit a file.

**Tech Stack:** Spring Boot 3.3, Spring Kafka 3.x (manual offset commit), JavaParser 3.25, Spring Data MongoDB, JdbcClient, Mockito (unit tests), Testcontainers Kafka + Postgres + MongoDB (integration test)

**Prerequisite:** P1 (schema), P2 (service registry), P3 (IngestionJob record + Kafka topics) complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   └── ingestion/
│       ├── ParsedModel.java          (record: raw class model from JavaParser)
│       ├── FactBatch.java            (record: normalised facts ready to write)
│       ├── GitHubSourceFetcher.java  (fetches .java files from GitHub Contents API)
│       ├── JavaParserService.java    (JavaParser + SymbolSolver wrapper)
│       ├── FactExtractor.java        (endpoints, dependencies, outbound calls)
│       ├── PeripheralDetector.java   (Tier 1 / 2 / 3 classification)
│       ├── DualWriteService.java     (atomic Postgres + MongoDB write)
│       ├── PrWorkerConsumer.java     (high-priority Kafka consumer)
│       └── BatchWorkerConsumer.java  (low-priority Kafka consumer)
└── test/java/io/testseer/backend/
    └── ingestion/
        ├── FactExtractorTest.java
        ├── PeripheralDetectorTest.java
        ├── DualWriteServiceTest.java
        └── WorkerConsumerIntegrationTest.java
```

---

### Task 1: `ParsedModel` and `FactBatch` records

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/ParsedModel.java`
- Create: `src/main/java/io/testseer/backend/ingestion/FactBatch.java`

- [ ] **Step 1: Create `ParsedModel.java`**

```java
package io.testseer.backend.ingestion;

import java.util.List;
import java.util.Map;

public record ParsedModel(
        String filePath,
        String classFqn,
        List<String> annotations,
        List<String> constructorParamTypes,
        List<String> fieldInjectionTypes,
        List<EndpointDef> endpoints,
        List<OutboundCallDef> outboundCalls,
        boolean parseError,
        String parseErrorDetail
) {
    public record EndpointDef(String httpMethod, String path, String methodName) {}
    public record OutboundCallDef(String clientType, String httpMethod, String path, String sourceMethod) {}
}
```

- [ ] **Step 2: Create `FactBatch.java`**

```java
package io.testseer.backend.ingestion;

import java.util.List;

public record FactBatch(
        String jobId,
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        String snapshotType,       // "BASELINE" | "DELTA"
        List<SymbolFact> symbolFacts,
        List<OutboundCallFact> outboundCallFacts,
        List<PeripheralFact> peripheralFacts,
        List<UnsupportedConstructFact> unsupportedConstructFacts
) {
    public record SymbolFact(
            String filePath, String symbolFqn, String symbolKind,
            String attributes, String evidenceSource, double confidence
    ) {}

    public record OutboundCallFact(
            String sourceSymbol, String httpMethod, String path,
            String evidenceSource, double confidence
    ) {}

    public record PeripheralFact(
            String peripheralType, int detectionTier,
            String detectionSignals, String prerequisiteText, String reasonCode
    ) {}

    public record UnsupportedConstructFact(
            String filePath, String reasonCode, String detail
    ) {}
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/
git commit -m "feat: add ParsedModel and FactBatch records for analysis worker pipeline"
```

---

### Task 2: `GitHubSourceFetcher`

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/GitHubSourceFetcher.java`

- [ ] **Step 1: Create `GitHubSourceFetcher.java`**

```java
package io.testseer.backend.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class GitHubSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubSourceFetcher.class);

    private final RestClient restClient;

    public GitHubSourceFetcher(
            @Value("${testseer.github.token:}") String githubToken) {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("Authorization",
                        githubToken.isBlank() ? "" : "Bearer " + githubToken)
                .build();
    }

    // package-visible for testing
    GitHubSourceFetcher(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Returns the decoded file content for each .java file in changedFiles.
     * Files that fail to fetch are skipped with a warning.
     */
    public List<FetchedFile> fetchJavaFiles(
            String orgId, String repo, String commitSha,
            List<String> changedFiles) {

        return changedFiles.stream()
                .filter(f -> f.endsWith(".java"))
                .map(filePath -> fetch(orgId, repo, commitSha, filePath))
                .filter(f -> f != null)
                .toList();
    }

    private FetchedFile fetch(String orgId, String repo, String commitSha, String filePath) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/repos/{org}/{repo}/contents/{path}?ref={sha}",
                            orgId, repo, filePath, commitSha)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return null;
            String encoded = (String) response.get("content");
            String content = new String(Base64.getMimeDecoder().decode(encoded));
            return new FetchedFile(filePath, content);
        } catch (Exception ex) {
            log.warn("Failed to fetch {}/{}/{}: {}", orgId, repo, filePath, ex.getMessage());
            return null;
        }
    }

    public record FetchedFile(String path, String content) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/GitHubSourceFetcher.java
git commit -m "feat: add GitHubSourceFetcher to retrieve .java files from GitHub Contents API"
```

---

### Task 3: `JavaParserService`

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/JavaParserService.java`

- [ ] **Step 1: Create `JavaParserService.java`**

```java
package io.testseer.backend.ingestion;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JavaParserService {

    private final JavaParser parser;

    public JavaParserService() {
        this.parser = new JavaParser();
    }

    public ParsedModel parse(String filePath, String sourceContent) {
        ParseResult<CompilationUnit> result = parser.parse(sourceContent);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String detail = result.getProblems().isEmpty() ? "unknown parse error"
                    : result.getProblems().get(0).getMessage();
            return new ParsedModel(
                    filePath, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), true, detail
            );
        }

        CompilationUnit cu = result.getResult().get();
        Optional<ClassOrInterfaceDeclaration> primaryClass = cu.getPrimaryType()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t);

        if (primaryClass.isEmpty()) {
            return new ParsedModel(filePath, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), false, null);
        }

        ClassOrInterfaceDeclaration cls = primaryClass.get();
        String classFqn = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString() + "." + cls.getNameAsString())
                .orElse(cls.getNameAsString());

        List<String> annotations = cls.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();

        List<String> constructorParams = cls.getConstructors().stream()
                .findFirst()
                .map(c -> c.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .toList())
                .orElse(List.of());

        List<String> fieldInjections = cls.getFields().stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired")))
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getType().asString())
                .toList();

        List<ParsedModel.EndpointDef> endpoints = extractEndpoints(cls);
        List<ParsedModel.OutboundCallDef> outboundCalls = extractOutboundCalls(cls);

        return new ParsedModel(
                filePath, classFqn, annotations, constructorParams,
                fieldInjections, endpoints, outboundCalls, false, null
        );
    }

    private List<ParsedModel.EndpointDef> extractEndpoints(ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.EndpointDef> result = new ArrayList<>();
        String classMapping = cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .map(a -> annotationValue(a, ""))
                .findFirst().orElse("");

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                String httpMethod = switch (ann.getNameAsString()) {
                    case "GetMapping"     -> "GET";
                    case "PostMapping"    -> "POST";
                    case "PutMapping"     -> "PUT";
                    case "DeleteMapping"  -> "DELETE";
                    case "PatchMapping"   -> "PATCH";
                    case "RequestMapping" -> "GET";
                    default -> null;
                };
                if (httpMethod != null) {
                    String methodPath = annotationValue(ann, "");
                    result.add(new ParsedModel.EndpointDef(
                            httpMethod, classMapping + methodPath, method.getNameAsString()));
                }
            }
        }
        return result;
    }

    private List<ParsedModel.OutboundCallDef> extractOutboundCalls(
            ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.OutboundCallDef> result = new ArrayList<>();
        var CLIENTS = List.of("RestClient", "WebClient", "RestTemplate", "FeignClient");

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

    private static String annotationValue(AnnotationExpr ann, String defaultValue) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return ann.asSingleMemberAnnotationExpr().getMemberValue().toString()
                    .replace("\"", "");
        }
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value")
                              || p.getNameAsString().equals("path"))
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .findFirst().orElse(defaultValue);
        }
        return defaultValue;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/JavaParserService.java
git commit -m "feat: add JavaParserService to extract endpoints, deps, and outbound calls"
```

---

### Task 4: `FactExtractor`

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/FactExtractor.java`
- Create: `src/test/java/io/testseer/backend/ingestion/FactExtractorTest.java`

- [ ] **Step 1: Write failing tests**

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=FactExtractorTest -q
```

Expected: All 4 FAIL.

- [ ] **Step 3: Create `FactExtractor.java`**

```java
package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class FactExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.SymbolFact> extractSymbolFacts(ParsedModel model) {
        List<FactBatch.SymbolFact> result = new ArrayList<>();

        if (model.classFqn() != null) {
            result.add(new FactBatch.SymbolFact(
                    model.filePath(), model.classFqn(), "CLASS",
                    toJson(Map.of("annotations", model.annotations())),
                    "javaparser", 1.0
            ));

            for (ParsedModel.EndpointDef ep : model.endpoints()) {
                String fqn = model.classFqn() + "#" + ep.methodName();
                result.add(new FactBatch.SymbolFact(
                        model.filePath(), fqn, "ENDPOINT",
                        toJson(Map.of("httpMethod", ep.httpMethod(), "path", ep.path())),
                        "javaparser", 1.0
                ));
            }
        }
        return result;
    }

    public List<FactBatch.OutboundCallFact> extractOutboundCallFacts(ParsedModel model) {
        return model.outboundCalls().stream()
                .map(call -> new FactBatch.OutboundCallFact(
                        model.classFqn() != null ? model.classFqn() : model.filePath(),
                        call.httpMethod(),
                        call.path(),
                        "javaparser",
                        0.9
                ))
                .toList();
    }

    public List<FactBatch.UnsupportedConstructFact> extractUnsupportedConstructFacts(
            ParsedModel model) {
        if (!model.parseError()) return List.of();
        return List.of(new FactBatch.UnsupportedConstructFact(
                model.filePath(), "PARSE_ERROR", model.parseErrorDetail()
        ));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=FactExtractorTest -q
```

Expected: All 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/FactExtractor.java \
        src/test/java/io/testseer/backend/ingestion/FactExtractorTest.java
git commit -m "feat: add FactExtractor to derive SymbolFacts and OutboundCallFacts from ParsedModel"
```

---

### Task 5: `PeripheralDetector`

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/PeripheralDetector.java`
- Create: `src/test/java/io/testseer/backend/ingestion/PeripheralDetectorTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeripheralDetectorTest {

    private final PeripheralDetector detector = new PeripheralDetector();

    @Test
    void kafkaListener_emitsTier1() {
        ParsedModel model = modelWithAnnotations(List.of("KafkaListener", "Service"));
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.peripheralType().equals("kafka") &&
                f.detectionTier() == 1 &&
                f.prerequisiteText().contains("Testcontainers")
        );
    }

    @Test
    void redisTemplate_emitsTier1() {
        ParsedModel model = modelWithFieldType("RedisTemplate");
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.peripheralType().equals("redis") && f.detectionTier() == 1);
    }

    @Test
    void oracleJdbcDriver_emitsTier2() {
        ParsedModel model = modelWithAnnotations(List.of("Service"));
        model = modelWithClassContent(model, "oracle.jdbc.OracleDriver");
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.peripheralType().equals("oracle") &&
                f.detectionTier() == 2 &&
                f.prerequisiteText().contains("Verify")
        );
    }

    @Test
    void springCloudConfig_emitsTier3() {
        ParsedModel model = modelWithAnnotations(List.of("EnableConfigServer"));
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.detectionTier() == 3 &&
                f.prerequisiteText().contains(".testseer/config.yml")
        );
    }

    @Test
    void plainSpringController_emitsNoPeripherals() {
        ParsedModel model = modelWithAnnotations(List.of("RestController"));
        assertThat(detector.detect(model)).isEmpty();
    }

    private static ParsedModel modelWithAnnotations(List<String> annotations) {
        return new ParsedModel("Svc.java", "com.example.Svc",
                annotations, List.of(), List.of(), List.of(), List.of(), false, null);
    }

    private static ParsedModel modelWithFieldType(String type) {
        return new ParsedModel("Svc.java", "com.example.Svc",
                List.of(), List.of(), List.of(type), List.of(), List.of(), false, null);
    }

    private static ParsedModel modelWithClassContent(ParsedModel base, String keyword) {
        // Simulate oracle detection via constructor params or field types
        return new ParsedModel(base.filePath(), base.classFqn(),
                base.annotations(), List.of(keyword), base.fieldInjectionTypes(),
                base.endpoints(), base.outboundCalls(), false, null);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=PeripheralDetectorTest -q
```

Expected: All 5 FAIL.

- [ ] **Step 3: Create `PeripheralDetector.java`**

```java
package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class PeripheralDetector {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.PeripheralFact> detect(ParsedModel model) {
        List<FactBatch.PeripheralFact> result = new ArrayList<>();
        List<String> all = allSignals(model);

        // Tier 1 — high confidence, direct Testcontainers recommendation
        checkTier1(result, all, "KafkaListener", "kafka",
                "Use Testcontainers Kafka (org.testcontainers:kafka) for this service");
        checkTier1(result, all, "RabbitListener", "rabbitmq",
                "Use Testcontainers RabbitMQ for this service");
        checkTier1ByType(result, all, "RedisTemplate", "redis",
                "Use Testcontainers Redis for this service");
        checkTier1ByType(result, all, "MongoTemplate", "mongodb",
                "Use Testcontainers MongoDB for this service");
        checkTier1(result, all, "AmazonS3", "s3",
                "Use LocalStack (Testcontainers) for this service");
        if (all.contains("Entity") && all.stream().anyMatch(s -> s.contains("postgresql"))) {
            result.add(tier1("postgres", List.of("@Entity", "postgresql dialect"),
                    "Use Testcontainers PostgreSQL for this service"));
        }

        // Tier 2 — possible on-prem, verify before using Testcontainers
        if (all.stream().anyMatch(s -> s.toLowerCase().contains("oracle"))) {
            result.add(tier2("oracle", List.of("oracle.jdbc"),
                    "Verify before using Testcontainers — Oracle may be on-prem at your org",
                    "oracle.jdbc.OracleDriver detected"));
        }
        if (all.stream().anyMatch(s -> s.toLowerCase().contains("sqlserver"))) {
            result.add(tier2("sqlserver", List.of("sqlserver"),
                    "Verify before using Testcontainers — SQL Server may be on-prem",
                    "SQL Server driver detected"));
        }

        // Tier 3 — manual setup required
        if (all.stream().anyMatch(s -> s.contains("EnableConfigServer") || s.contains("ConfigServer"))) {
            result.add(tier3("spring-cloud-config",
                    List.of("@EnableConfigServer"),
                    "Manual setup required — declare peripheral in .testseer/config.yml",
                    "SPRING_CLOUD_CONFIG"));
        }

        return result;
    }

    private static void checkTier1(List<FactBatch.PeripheralFact> result,
                                   List<String> signals, String signal,
                                   String type, String text) {
        if (signals.contains(signal)) {
            result.add(tier1(type, List.of(signal), text));
        }
    }

    private static void checkTier1ByType(List<FactBatch.PeripheralFact> result,
                                          List<String> signals, String typeKeyword,
                                          String type, String text) {
        if (signals.stream().anyMatch(s -> s.contains(typeKeyword))) {
            result.add(tier1(type, List.of(typeKeyword), text));
        }
    }

    private static FactBatch.PeripheralFact tier1(String type, List<String> signals, String text) {
        return new FactBatch.PeripheralFact(type, 1,
                signals.toString(), text, null);
    }

    private static FactBatch.PeripheralFact tier2(String type, List<String> signals,
                                                    String text, String reasonCode) {
        return new FactBatch.PeripheralFact(type, 2, signals.toString(), text, reasonCode);
    }

    private static FactBatch.PeripheralFact tier3(String type, List<String> signals,
                                                    String text, String reasonCode) {
        return new FactBatch.PeripheralFact(type, 3, signals.toString(), text, reasonCode);
    }

    private static List<String> allSignals(ParsedModel model) {
        return Stream.of(
                model.annotations(),
                model.constructorParamTypes(),
                model.fieldInjectionTypes()
        ).flatMap(List::stream).toList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=PeripheralDetectorTest -q
```

Expected: All 5 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/PeripheralDetector.java \
        src/test/java/io/testseer/backend/ingestion/PeripheralDetectorTest.java
git commit -m "feat: add PeripheralDetector with Tier 1/2/3 signal classification"
```

---

### Task 6: `DualWriteService`

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/DualWriteService.java`
- Create: `src/test/java/io/testseer/backend/ingestion/DualWriteServiceTest.java`

- [ ] **Step 1: Write failing integration test**

```java
package io.testseer.backend.ingestion;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class DualWriteServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired DualWriteService dualWriteService;
    @Autowired ServiceRegistryService registryService;
    @Autowired JdbcClient jdbcClient;
    @Autowired MongoTemplate mongoTemplate;

    String serviceId;

    @BeforeEach
    void setup() {
        jdbcClient.sql("DELETE FROM outbound_call_facts").update();
        jdbcClient.sql("DELETE FROM symbol_facts").update();
        jdbcClient.sql("DELETE FROM peripheral_facts").update();
        jdbcClient.sql("DELETE FROM service_registry").update();
        mongoTemplate.dropCollection("parsed_models");

        serviceId = registryService.register(new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                "service", List.of("src/main/java"), null, null
        )).serviceId();
    }

    @Test
    void write_persists_symbolFacts_to_postgres() {
        FactBatch batch = new FactBatch(
                "job-001", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(new FactBatch.SymbolFact(
                        "OrderController.java", "com.example.OrderController",
                        "CLASS", "{}", "javaparser", 1.0
                )),
                List.of(), List.of(), List.of()
        );

        dualWriteService.write(batch, List.of());

        long count = jdbcClient
                .sql("SELECT COUNT(*) FROM symbol_facts WHERE service_id = :id")
                .param("id", serviceId)
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void write_persists_parsedModel_to_mongodb() {
        ParsedModel model = new ParsedModel(
                "OrderController.java", "com.example.OrderController",
                List.of("RestController"), List.of(), List.of(),
                List.of(), List.of(), false, null
        );
        FactBatch batch = new FactBatch(
                "job-002", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of()
        );

        dualWriteService.write(batch, List.of(model));

        long count = mongoTemplate.getCollection("parsed_models")
                .countDocuments(new Document("serviceId", serviceId));
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DualWriteServiceTest -q
```

Expected: Both tests FAIL — `DualWriteService` does not exist.

- [ ] **Step 3: Create `DualWriteService.java`**

```java
package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class DualWriteService {

    private static final Logger log = LoggerFactory.getLogger(DualWriteService.class);

    private final JdbcClient db;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;

    public DualWriteService(JdbcClient db, MongoTemplate mongo, ObjectMapper mapper) {
        this.db = db;
        this.mongo = mongo;
        this.mapper = mapper;
    }

    @Transactional
    public void write(FactBatch batch, List<ParsedModel> models) {
        writeSymbolFacts(batch);
        writeOutboundCallFacts(batch);
        writePeripheralFacts(batch);
        writeUnsupportedConstructFacts(batch);
        writeParsedModels(batch, models);
    }

    private void writeSymbolFacts(FactBatch batch) {
        String sql = """
                INSERT INTO symbol_facts
                  (org_id, repo, service_id, commit_sha, file_path, symbol_fqn,
                   symbol_kind, snapshot_type, attributes, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :filePath, :symbolFqn,
                        :symbolKind, :snapshotType, :attributes::jsonb, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.SymbolFact f : batch.symbolFacts()) {
            db.sql(sql)
                    .param("orgId",          batch.orgId())
                    .param("repo",           batch.repo())
                    .param("serviceId",      batch.serviceId())
                    .param("commitSha",      batch.commitSha())
                    .param("filePath",       f.filePath())
                    .param("symbolFqn",      f.symbolFqn())
                    .param("symbolKind",     f.symbolKind())
                    .param("snapshotType",   batch.snapshotType())
                    .param("attributes",     f.attributes())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence",     f.confidence())
                    .update();
        }
    }

    private void writeOutboundCallFacts(FactBatch batch) {
        String sql = """
                INSERT INTO outbound_call_facts
                  (org_id, repo, service_id, commit_sha, source_symbol,
                   http_method, path, snapshot_type, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :sourceSymbol,
                        :httpMethod, :path, :snapshotType, :evidenceSource, :confidence)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.OutboundCallFact f : batch.outboundCallFacts()) {
            db.sql(sql)
                    .param("orgId",          batch.orgId())
                    .param("repo",           batch.repo())
                    .param("serviceId",      batch.serviceId())
                    .param("commitSha",      batch.commitSha())
                    .param("sourceSymbol",   f.sourceSymbol())
                    .param("httpMethod",     f.httpMethod())
                    .param("path",           f.path())
                    .param("snapshotType",   batch.snapshotType())
                    .param("evidenceSource", f.evidenceSource())
                    .param("confidence",     f.confidence())
                    .update();
        }
    }

    private void writePeripheralFacts(FactBatch batch) {
        String sql = """
                INSERT INTO peripheral_facts
                  (org_id, service_id, commit_sha, peripheral_type, detection_tier,
                   detection_signals, prerequisite_text, reason_code)
                VALUES (:orgId, :serviceId, :commitSha, :type, :tier,
                        :signals::jsonb, :text, :reasonCode)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.PeripheralFact f : batch.peripheralFacts()) {
            db.sql(sql)
                    .param("orgId",       batch.orgId())
                    .param("serviceId",   batch.serviceId())
                    .param("commitSha",   batch.commitSha())
                    .param("type",        f.peripheralType())
                    .param("tier",        f.detectionTier())
                    .param("signals",     "[\"" + f.detectionSignals() + "\"]")
                    .param("text",        f.prerequisiteText())
                    .param("reasonCode",  f.reasonCode())
                    .update();
        }
    }

    private void writeUnsupportedConstructFacts(FactBatch batch) {
        String sql = """
                INSERT INTO unsupported_construct_facts
                  (org_id, service_id, commit_sha, file_path, reason_code, detail)
                VALUES (:orgId, :serviceId, :commitSha, :filePath, :reasonCode, :detail)
                ON CONFLICT DO NOTHING
                """;
        for (FactBatch.UnsupportedConstructFact f : batch.unsupportedConstructFacts()) {
            db.sql(sql)
                    .param("orgId",      batch.orgId())
                    .param("serviceId",  batch.serviceId())
                    .param("commitSha",  batch.commitSha())
                    .param("filePath",   f.filePath())
                    .param("reasonCode", f.reasonCode())
                    .param("detail",     f.detail())
                    .update();
        }
    }

    private void writeParsedModels(FactBatch batch, List<ParsedModel> models) {
        if (models.isEmpty()) return;
        try {
            Document doc = new Document();
            doc.put("_id", batch.orgId() + "/" + batch.repo() + "/" +
                           batch.serviceId() + "/" + batch.commitSha());
            doc.put("orgId",       batch.orgId());
            doc.put("repo",        batch.repo());
            doc.put("serviceId",   batch.serviceId());
            doc.put("commitSha",   batch.commitSha());
            doc.put("indexedAt",   Instant.now().toString());
            doc.put("models",      mapper.writeValueAsString(models));

            mongo.getCollection("parsed_models")
                 .replaceOne(new Document("_id", doc.get("_id")), doc,
                         new com.mongodb.client.model.ReplaceOptions().upsert(true));
        } catch (Exception ex) {
            log.error("MongoDB write failed for {}/{}: {}",
                    batch.serviceId(), batch.commitSha(), ex.getMessage());
            throw new RuntimeException("MongoDB write failed", ex);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=DualWriteServiceTest -q
```

Expected: Both PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/DualWriteService.java \
        src/test/java/io/testseer/backend/ingestion/DualWriteServiceTest.java
git commit -m "feat: add DualWriteService for atomic Postgres + MongoDB fact persistence"
```

---

### Task 7: `PrWorkerConsumer` and `BatchWorkerConsumer`

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/PrWorkerConsumer.java`
- Create: `src/main/java/io/testseer/backend/ingestion/BatchWorkerConsumer.java`

- [ ] **Step 1: Create `PrWorkerConsumer.java`**

```java
package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PrWorkerConsumer {

    private static final Logger log = LoggerFactory.getLogger(PrWorkerConsumer.class);

    private final WorkerPipeline pipeline;

    public PrWorkerConsumer(WorkerPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(
            topics = KafkaTopicsConfig.TOPIC_PR,
            groupId = "testseer-workers-pr",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IngestionJob> record, Acknowledgment ack) {
        IngestionJob job = record.value();
        log.info("PR worker processing job={} service={} pr={}",
                job.jobId(), job.serviceId(), job.prNumber());
        try {
            pipeline.process(job);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("PR worker failed job={}: {}", job.jobId(), ex.getMessage(), ex);
            // Do NOT acknowledge — Kafka will redeliver
        }
    }
}
```

- [ ] **Step 2: Create `WorkerPipeline.java`** (shared logic extracted to avoid duplication)

```java
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
```

- [ ] **Step 3: Create `BatchWorkerConsumer.java`**

```java
package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaTopicsConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class BatchWorkerConsumer {

    private static final Logger log = LoggerFactory.getLogger(BatchWorkerConsumer.class);

    private final WorkerPipeline pipeline;

    public BatchWorkerConsumer(WorkerPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(
            topics = KafkaTopicsConfig.TOPIC_BATCH,
            groupId = "testseer-workers-batch",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IngestionJob> record, Acknowledgment ack) {
        IngestionJob job = record.value();
        log.info("Batch worker processing job={} service={}", job.jobId(), job.serviceId());
        try {
            pipeline.process(job);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Batch worker failed job={}: {}", job.jobId(), ex.getMessage(), ex);
        }
    }
}
```

- [ ] **Step 4: Add `KafkaListenerContainerFactory` with manual ack mode**

Create `src/main/java/io/testseer/backend/ingestion/KafkaConsumerConfig.java`:

```java
package io.testseer.backend.ingestion;

import io.testseer.backend.webhook.IngestionJob;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, IngestionJob> ingestionJobConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "io.testseer.backend");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, IngestionJob.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, IngestionJob>
            kafkaListenerContainerFactory(
                    ConsumerFactory<String, IngestionJob> ingestionJobConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, IngestionJob>();
        factory.setConsumerFactory(ingestionJobConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
```

- [ ] **Step 5: Run all ingestion tests**

```bash
mvn test -Dtest="FactExtractorTest,PeripheralDetectorTest,DualWriteServiceTest" -q
```

Expected: All PASS.

- [ ] **Step 6: Compile full project to verify no wiring errors**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/
git commit -m "feat: add PR and batch Kafka consumers with shared WorkerPipeline (manual offset commit)"
```

---

### Task 8: `analysis_runs` tracking and Pub/Sub event publication

**Why this task exists:** `FreshnessResolver` (P6) reads `analysis_runs` to compute `INDEXING`/`COMPLETE`/`NOT_INDEXED`. `CacheInvalidationListener` (P6) consumes a Pub/Sub event to evict Redis. Neither works unless the worker writes here.

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/AnalysisRunTracker.java`
- Modify: `src/main/java/io/testseer/backend/ingestion/WorkerPipeline.java`
- Modify: `src/test/java/io/testseer/backend/ingestion/DualWriteServiceTest.java`

- [ ] **Step 1: Create `AnalysisRunTracker.java`**

```java
package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class AnalysisRunTracker {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunTracker.class);
    private static final String TOPIC = "testseer.index.complete";

    private final JdbcClient db;
    private final ObjectMapper mapper;

    public AnalysisRunTracker(JdbcClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public void markQueued(IngestionJob job) {
        db.sql("""
                INSERT INTO analysis_runs
                  (job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
                VALUES (:jobId, :orgId, :serviceId, :commitSha, :jobType, 'QUEUED', :attempt, :enqueuedAt)
                ON CONFLICT (job_id) DO NOTHING
                """)
                .param("jobId",       job.jobId())
                .param("orgId",       job.orgId())
                .param("serviceId",   job.serviceId())
                .param("commitSha",   job.commitSha())
                .param("jobType",     job.jobType())
                .param("attempt",     job.attempt())
                .param("enqueuedAt",  job.enqueuedAt())
                .update();
    }

    public void markRunning(String jobId) {
        db.sql("UPDATE analysis_runs SET status = 'RUNNING', started_at = now() WHERE job_id = :id")
                .param("id", jobId)
                .update();
    }

    public void markComplete(String jobId) {
        db.sql("UPDATE analysis_runs SET status = 'COMPLETE', completed_at = now() WHERE job_id = :id")
                .param("id", jobId)
                .update();
    }

    public void markFailed(String jobId, String errorDetail) {
        db.sql("""
                UPDATE analysis_runs
                SET status = 'FAILED', completed_at = now(), error_detail = :detail
                WHERE job_id = :id
                """)
                .param("id",     jobId)
                .param("detail", errorDetail)
                .update();
    }
}
```

- [ ] **Step 2: Update `WorkerPipeline.process()` to track run state**

Replace the `process` method body in `WorkerPipeline.java` with:

```java
private final AnalysisRunTracker runTracker;

// Add runTracker to constructor:
public WorkerPipeline(GitHubSourceFetcher fetcher,
                      JavaParserService parserService,
                      FactExtractor factExtractor,
                      PeripheralDetector peripheralDetector,
                      DualWriteService dualWriteService,
                      AnalysisRunTracker runTracker) {
    this.fetcher = fetcher;
    this.parserService = parserService;
    this.factExtractor = factExtractor;
    this.peripheralDetector = peripheralDetector;
    this.dualWriteService = dualWriteService;
    this.runTracker = runTracker;
}

public void process(IngestionJob job) {
    runTracker.markQueued(job);
    runTracker.markRunning(job.jobId());
    try {
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
        runTracker.markComplete(job.jobId());
        log.info("Job {} complete: {} symbol facts, {} peripheral facts",
                job.jobId(), symbolFacts.size(), peripheralFacts.size());
    } catch (Exception ex) {
        runTracker.markFailed(job.jobId(), ex.getMessage());
        throw ex;  // re-throw so Kafka consumer does not acknowledge
    }
}
```

- [ ] **Step 3: Add a test verifying analysis_runs is updated**

Add to `DualWriteServiceTest`:

```java
@Autowired AnalysisRunTracker runTracker;

@Test
void markComplete_sets_COMPLETE_status_in_analysis_runs() {
    // Insert a QUEUED run first
    jdbcClient.sql("""
        INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
        VALUES ('j-track-001', 'acme', :svcId, 'abc', 'PR', 'QUEUED', 1, now())
        """).param("svcId", serviceId).update();

    runTracker.markRunning("j-track-001");
    runTracker.markComplete("j-track-001");

    String status = jdbcClient.sql(
            "SELECT status FROM analysis_runs WHERE job_id = 'j-track-001'")
            .query(String.class).single();

    assertThat(status).isEqualTo("COMPLETE");
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest="DualWriteServiceTest#markComplete_sets_COMPLETE_status_in_analysis_runs" -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/ingestion/AnalysisRunTracker.java \
        src/main/java/io/testseer/backend/ingestion/WorkerPipeline.java \
        src/test/java/io/testseer/backend/ingestion/DualWriteServiceTest.java
git commit -m "feat: add AnalysisRunTracker to record QUEUED/RUNNING/COMPLETE in analysis_runs for freshness resolution"
```

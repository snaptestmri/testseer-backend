# P12: Test Gap Detection

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /v1/gaps?serviceId=` — identify production classes and endpoints that have no corresponding test class. Uses the already-indexed `symbol_facts` table: production classes live under `src/main/java/`, test classes under `src/test/java/`. Returns a `GapReport` with counts and a list of untested classes, wrapped in `ResponseEnvelope`.

**Architecture:** `GapDetectionService` runs two SQL queries against `symbol_facts` (production classes, test classes for the latest complete run), then matches them in Java using standard test naming conventions (`FooTest`, `FooTests`, `FooIT`, `TestFoo`). `GapDetectionController` exposes the endpoint. Both follow the existing `FactQueryController` freshness + cache pattern.

**Tech Stack:** Spring Boot 3.3, JdbcClient, MockMvc (unit tests).

**Prerequisite:** P1–P11 complete (V7 migration needed for `analysis_runs`).

**Shared utility:** Reuse [`TestClassMatcher.java`](../../../testseer-backend/src/main/java/io/testseer/backend/analysis/TestClassMatcher.java) from P11 — do not duplicate naming convention logic.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   └── analysis/
│       ├── GapDetectionController.java   (new)
│       ├── GapDetectionService.java      (new)
│       └── GapReport.java               (new — response records)
└── test/java/io/testseer/backend/
    └── analysis/
        ├── GapDetectionServiceTest.java
        └── GapDetectionControllerTest.java
```

---

### Task 1: `GapReport` records + `GapDetectionService`

**Files:**
- Create: `src/main/java/io/testseer/backend/analysis/GapReport.java`
- Create: `src/main/java/io/testseer/backend/analysis/GapDetectionService.java`
- Create: `src/test/java/io/testseer/backend/analysis/GapDetectionServiceTest.java`

- [ ] **Step 1: Create `GapReport.java`**

```java
package io.testseer.backend.analysis;

import java.util.List;

public record GapReport(
        String serviceId,
        String commitSha,             // SHA of the latest complete run used for analysis
        int productionClassCount,
        int testedClassCount,
        int untestedClassCount,
        List<ClassGap> gaps
) {
    public record ClassGap(
            String classFqn,
            String filePath,
            String kind    // "CLASS" | "ENDPOINT_CONTROLLER" (class that has @Controller/@RestController)
    ) {}
}
```

- [ ] **Step 2: Write failing service tests**

Create `src/test/java/io/testseer/backend/analysis/GapDetectionServiceTest.java`:

```java
package io.testseer.backend.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GapDetectionServiceTest {

    private final GapDetectionService service = new GapDetectionService(null);

    // Test the static matching logic directly — no DB needed

    @Test
    void hasTest_matchesFooTest() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderController",
                Set.of("io.orders.OrderControllerTest"))).isTrue();
    }

    @Test
    void hasTest_matchesFooTests() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderService",
                Set.of("io.orders.OrderServiceTests"))).isTrue();
    }

    @Test
    void hasTest_matchesFooIT() {
        assertThat(GapDetectionService.hasTest("io.orders.PaymentClient",
                Set.of("io.orders.PaymentClientIT"))).isTrue();
    }

    @Test
    void hasTest_matchesTestFoo() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderRepository",
                Set.of("io.orders.TestOrderRepository"))).isTrue();
    }

    @Test
    void hasTest_returnsFalse_whenNoMatch() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderController",
                Set.of("io.orders.PaymentServiceTest"))).isFalse();
    }

    @Test
    void hasTest_returnsFalse_whenTestSetEmpty() {
        assertThat(GapDetectionService.hasTest("io.orders.OrderController",
                Set.of())).isFalse();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GapDetectionServiceTest -q 2>&1 | tail -10
```

Expected: All 6 FAIL — class does not exist.

- [ ] **Step 4: Create `GapDetectionService.java`**

```java
package io.testseer.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GapDetectionService {

    private static final Logger log = LoggerFactory.getLogger(GapDetectionService.class);

    private final JdbcClient db;

    public GapDetectionService(JdbcClient db) {
        this.db = db;
    }

    public GapReport buildReport(String serviceId) {

        // Resolve the latest complete commit for this service
        String latestSha = db.sql("""
                SELECT commit_sha FROM analysis_runs
                WHERE service_id = :svcId AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("svcId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);

        if (latestSha == null) {
            return new GapReport(serviceId, null, 0, 0, 0, List.of());
        }

        // Production classes (src/main/java/)
        List<GapReport.ClassGap> productionClasses = db.sql("""
                SELECT DISTINCT ON (symbol_fqn) symbol_fqn, file_path, attributes::text
                FROM symbol_facts
                WHERE service_id = :svcId
                  AND symbol_kind = 'CLASS'
                  AND file_path LIKE 'src/main/java/%'
                  AND commit_sha = :sha
                ORDER BY symbol_fqn
                """)
                .param("svcId", serviceId)
                .param("sha",   latestSha)
                .query((rs, row) -> new GapReport.ClassGap(
                        rs.getString("symbol_fqn"),
                        rs.getString("file_path"),
                        isController(rs.getString("attributes")) ? "ENDPOINT_CONTROLLER" : "CLASS"
                ))
                .list();

        // Test classes (src/test/java/ OR FQN ends with Test/IT)
        Set<String> testFqns = db.sql("""
                SELECT DISTINCT symbol_fqn
                FROM symbol_facts
                WHERE service_id = :svcId
                  AND symbol_kind = 'CLASS'
                  AND (file_path LIKE 'src/test/java/%'
                       OR symbol_fqn LIKE '%Test'
                       OR symbol_fqn LIKE '%Tests'
                       OR symbol_fqn LIKE '%IT')
                  AND commit_sha = :sha
                """)
                .param("svcId", serviceId)
                .param("sha",   latestSha)
                .query(String.class)
                .list()
                .stream().collect(Collectors.toSet());

        // Find gaps: production classes with no matching test class
        List<GapReport.ClassGap> gaps = productionClasses.stream()
                .filter(c -> !hasTest(c.classFqn(), testFqns))
                .toList();

        int tested   = productionClasses.size() - gaps.size();
        int untested = gaps.size();

        log.info("Gap report for {}: {}/{} classes have tests",
                serviceId, tested, productionClasses.size());

        return new GapReport(serviceId, latestSha,
                productionClasses.size(), tested, untested, gaps);
    }

    /**
     * Returns true if any test class FQN matches standard naming conventions for the
     * given production class FQN. Patterns: FooTest, FooTests, FooIT, TestFoo.
     */
    static boolean hasTest(String classFqn, Set<String> testFqns) {
        String simpleName = simpleName(classFqn);
        return testFqns.stream().anyMatch(t -> {
            String ts = simpleName(t);
            return ts.equals(simpleName + "Test")
                    || ts.equals(simpleName + "Tests")
                    || ts.equals(simpleName + "IT")
                    || ts.startsWith("Test" + simpleName);
        });
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private boolean isController(String attributesJson) {
        if (attributesJson == null) return false;
        return attributesJson.contains("Controller") || attributesJson.contains("RestController");
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GapDetectionServiceTest -q 2>&1 | tail -10
```

Expected: All 6 PASS.

- [ ] **Step 6: Compile full project**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/analysis/GapReport.java \
        src/main/java/io/testseer/backend/analysis/GapDetectionService.java \
        src/test/java/io/testseer/backend/analysis/GapDetectionServiceTest.java
git commit -m "feat: add GapDetectionService matching production classes to test classes using standard naming conventions"
```

---

### Task 2: `GapDetectionController` + MockMvc tests + Swagger

**Files:**
- Create: `src/main/java/io/testseer/backend/analysis/GapDetectionController.java`
- Create: `src/test/java/io/testseer/backend/analysis/GapDetectionControllerTest.java`

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.analysis;

import io.testseer.backend.query.FreshnessResolver;
import io.testseer.backend.query.FreshnessStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GapDetectionController.class)
class GapDetectionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean GapDetectionService gapService;
    @MockBean FreshnessResolver freshnessResolver;

    @Test
    void gaps_returns200_withReport() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(gapService.buildReport("svc-001")).thenReturn(
                new GapReport("svc-001", "abc123", 10, 7, 3,
                        List.of(
                                new GapReport.ClassGap("io.orders.EmailService",
                                        "src/main/java/io/orders/EmailService.java", "CLASS"),
                                new GapReport.ClassGap("io.orders.ReportController",
                                        "src/main/java/io/orders/ReportController.java",
                                        "ENDPOINT_CONTROLLER"),
                                new GapReport.ClassGap("io.orders.AuditLogger",
                                        "src/main/java/io/orders/AuditLogger.java", "CLASS")
                        )));

        mockMvc.perform(get("/v1/gaps").param("serviceId", "svc-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.productionClassCount").value(10))
                .andExpect(jsonPath("$.data.untestedClassCount").value(3))
                .andExpect(jsonPath("$.data.gaps.length()").value(3))
                .andExpect(jsonPath("$.data.gaps[1].kind").value("ENDPOINT_CONTROLLER"));
    }

    @Test
    void gaps_returns404_whenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/gaps").param("serviceId", "svc-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void gaps_returns202_whenIndexing() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.INDEXING);

        mockMvc.perform(get("/v1/gaps").param("serviceId", "svc-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.freshnessStatus").value("INDEXING"));
    }

    @Test
    void gaps_returns400_whenServiceIdMissing() throws Exception {
        mockMvc.perform(get("/v1/gaps"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GapDetectionControllerTest -q 2>&1 | tail -10
```

- [ ] **Step 3: Create `GapDetectionController.java`**

```java
package io.testseer.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.query.FreshnessResolver;
import io.testseer.backend.query.FreshnessStatus;
import io.testseer.backend.query.ResponseEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Analysis", description = "Impact analysis and test planning based on the indexed knowledge graph")
@RestController
@RequestMapping("/v1/gaps")
public class GapDetectionController {

    private final GapDetectionService gapService;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public GapDetectionController(GapDetectionService gapService,
                                   FreshnessResolver freshnessResolver,
                                   @Value("${testseer.stale-threshold-minutes:60}")
                                       int staleThresholdMinutes) {
        this.gapService            = gapService;
        this.freshnessResolver     = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(
        summary = "Detect test coverage gaps",
        description = """
            Compares production classes (under src/main/java/) against test classes \
            (under src/test/java/ or matching standard naming conventions: FooTest, \
            FooTests, FooIT, TestFoo). Returns a report of production classes that \
            have no corresponding test class, with counts and a full list of gaps. \
            ENDPOINT_CONTROLLER gaps are especially high-priority — untested \
            controllers expose functionality with no verification. \
            Uses the most recent complete analysis run for this service.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Gap report generated",
            content = @Content(schema = @Schema(implementation = GapReport.class))),
        @ApiResponse(responseCode = "202", description = "Indexing in progress — results may be incomplete"),
        @ApiResponse(responseCode = "404", description = "Service not indexed"),
        @ApiResponse(responseCode = "400", description = "serviceId parameter is missing")
    })
    @GetMapping
    public ResponseEntity<ResponseEnvelope<GapReport>> gaps(
            @Parameter(description = "Service identifier", required = true)
            @RequestParam String serviceId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);

        return switch (status) {
            case NOT_INDEXED -> ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
            case INDEXING    -> ResponseEntity.status(202)
                    .body(ResponseEnvelope.indexing(null));
            default -> {
                GapReport report = gapService.buildReport(serviceId);
                yield ResponseEntity.ok(
                        ResponseEnvelope.of(null, report.commitSha(), status, report));
            }
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GapDetectionControllerTest -q 2>&1 | tail -10
```

Expected: All 4 PASS.

- [ ] **Step 5: Run full non-Docker suite**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="GapDetectionControllerTest,GapDetectionServiceTest,ImpactAnalysisControllerTest,ImpactAnalysisServiceTest,FactQueryControllerTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/analysis/GapDetectionController.java \
        src/test/java/io/testseer/backend/analysis/GapDetectionControllerTest.java
git commit -m "feat: add GET /v1/gaps — test coverage gap detection with 200/202/404 freshness responses and Swagger docs"
```

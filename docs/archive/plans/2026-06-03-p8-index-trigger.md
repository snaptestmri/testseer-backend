# P8: Manual On-Demand Indexing Trigger

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /admin/index/{serviceId}` — kick off a full repo index on demand. Fetches all `.java` files from GitHub using the Git Trees API, publishes to `TOPIC_BATCH`, and returns a jobId the caller can track via `GET /v1/status/{serviceId}`.

**Architecture:** Three layers. `GitHubTreeFetcher` (new) handles the GitHub Git Trees API call to enumerate all `.java` paths and resolve HEAD SHA. `IndexTriggerService` (new) orchestrates: load service → resolve commitSha → check for in-flight jobs → fetch tree → publish. `IndexTriggerController` (new) exposes the REST endpoint. `WorkerPipeline` (existing) gets a one-line fix to treat `MANUAL` jobs as BASELINE snapshots.

**Tech Stack:** Spring Boot 3.3, Spring Web (RestClient), JdbcClient, Kafka, MockMvc (unit tests).

**Prerequisite:** P1–P7 complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   ├── admin/
│   │   ├── IndexTriggerController.java   (new — POST /admin/index/{serviceId})
│   │   ├── IndexTriggerService.java      (new — orchestration)
│   │   ├── IndexTriggerRequest.java      (new — request record)
│   │   └── IndexTriggerResponse.java     (new — response record)
│   └── ingestion/
│       └── GitHubTreeFetcher.java        (new — Git Trees API + HEAD resolution)
└── test/java/io/testseer/backend/
    ├── admin/
    │   ├── IndexTriggerControllerTest.java
    │   └── IndexTriggerServiceTest.java
    └── ingestion/
        └── GitHubTreeFetcherTest.java
```

**Existing file modified:**
- `src/main/java/io/testseer/backend/ingestion/WorkerPipeline.java` — one-line fix: add `MANUAL` to BASELINE condition

---

### Task 1: `GitHubTreeFetcher` — tree enumeration and HEAD resolution

**Files:**
- Create: `src/main/java/io/testseer/backend/ingestion/GitHubTreeFetcher.java`
- Create: `src/test/java/io/testseer/backend/ingestion/GitHubTreeFetcherTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GitHubTreeFetcherTest {

    // Uses the package-visible constructor that accepts a pre-built RestClient mock.
    // Pattern mirrors GitHubSourceFetcher tests.

    @Test
    void fetchJavaPaths_filtersToJavaFiles() {
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        RestClient restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
                "truncated", false,
                "tree", List.of(
                        Map.of("path", "src/main/java/Foo.java",    "type", "blob"),
                        Map.of("path", "src/main/java/Bar.java",    "type", "blob"),
                        Map.of("path", "README.md",                  "type", "blob"),
                        Map.of("path", "src/main/java/pkg",          "type", "tree")
                )
        ));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        List<String> paths = fetcher.fetchJavaPaths("acme", "orders", "abc123");

        assertThat(paths).containsExactlyInAnyOrder(
                "src/main/java/Foo.java",
                "src/main/java/Bar.java"
        );
    }

    @Test
    void resolveHeadSha_returnsCommitSha() {
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        RestClient restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("sha", "deadbeef"));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        String sha = fetcher.resolveHeadSha("acme", "orders");

        assertThat(sha).isEqualTo("deadbeef");
    }

    @Test
    void fetchJavaPaths_logsWarningWhenTruncated() {
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        RestClient restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
                "truncated", true,
                "tree", List.of(Map.of("path", "src/Foo.java", "type", "blob"))
        ));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        // Should not throw — truncation is a warning, not a failure
        List<String> paths = fetcher.fetchJavaPaths("acme", "orders", "abc123");
        assertThat(paths).hasSize(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GitHubTreeFetcherTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — class does not exist.

- [ ] **Step 3: Create `GitHubTreeFetcher.java`**

```java
package io.testseer.backend.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class GitHubTreeFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubTreeFetcher.class);

    private final RestClient restClient;

    public GitHubTreeFetcher(
            @Value("${testseer.github.token:}") String githubToken) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (!githubToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + githubToken);
        }
        this.restClient = builder.build();
    }

    // package-visible for testing
    GitHubTreeFetcher(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Returns all .java file paths in the repo at the given commit SHA.
     * Uses GitHub's recursive tree API — one network call for the full tree.
     * Logs a warning if the tree is truncated (repo > ~100k files).
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchJavaPaths(String orgId, String repo, String commitSha) {
        Map<String, Object> response = restClient.get()
                .uri("/repos/{org}/{repo}/git/trees/{sha}?recursive=1",
                        orgId, repo, commitSha)
                .retrieve()
                .body(Map.class);

        if (response == null) return List.of();

        if (Boolean.TRUE.equals(response.get("truncated"))) {
            log.warn("Git tree response truncated for {}/{} — large repo, some files may be missed",
                    orgId, repo);
        }

        List<Map<String, String>> tree = (List<Map<String, String>>) response.get("tree");
        if (tree == null) return List.of();

        return tree.stream()
                .filter(entry -> "blob".equals(entry.get("type")))
                .map(entry -> entry.get("path"))
                .filter(path -> path != null && path.endsWith(".java"))
                .toList();
    }

    /**
     * Returns the SHA of the HEAD commit on the default branch.
     */
    @SuppressWarnings("unchecked")
    public String resolveHeadSha(String orgId, String repo) {
        Map<String, Object> response = restClient.get()
                .uri("/repos/{org}/{repo}/commits/HEAD", orgId, repo)
                .retrieve()
                .body(Map.class);

        if (response == null) throw new IllegalStateException(
                "GitHub returned null for HEAD commit of " + orgId + "/" + repo);

        return (String) response.get("sha");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GitHubTreeFetcherTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 5: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/ingestion/GitHubTreeFetcher.java \
        src/test/java/io/testseer/backend/ingestion/GitHubTreeFetcherTest.java
git commit -m "feat: add GitHubTreeFetcher for recursive .java file enumeration and HEAD SHA resolution"
```

---

### Task 2: `IndexTriggerService` + `WorkerPipeline` fix

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/IndexTriggerRequest.java`
- Create: `src/main/java/io/testseer/backend/admin/IndexTriggerResponse.java`
- Create: `src/main/java/io/testseer/backend/admin/IndexTriggerService.java`
- Create: `src/test/java/io/testseer/backend/admin/IndexTriggerServiceTest.java`
- Modify: `src/main/java/io/testseer/backend/ingestion/WorkerPipeline.java`

- [ ] **Step 1: Create request and response records**

```java
// src/main/java/io/testseer/backend/admin/IndexTriggerRequest.java
package io.testseer.backend.admin;

public record IndexTriggerRequest(
        String commitSha   // optional — null means resolve HEAD
) {}
```

```java
// src/main/java/io/testseer/backend/admin/IndexTriggerResponse.java
package io.testseer.backend.admin;

public record IndexTriggerResponse(
        String jobId,
        String serviceId,
        String commitSha,
        int fileCount
) {}
```

- [ ] **Step 2: Write failing service tests**

```java
package io.testseer.backend.admin;

import io.testseer.backend.ingestion.AnalysisRunTracker;
import io.testseer.backend.ingestion.GitHubTreeFetcher;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexTriggerServiceTest {

    @Mock ServiceRegistryService registryService;
    @Mock GitHubTreeFetcher treeFetcher;
    @Mock KafkaJobPublisher publisher;
    @Mock AnalysisRunTracker runTracker;
    @Mock JdbcClient db;

    @InjectMocks IndexTriggerService service;

    private ServiceEntry sampleEntry() {
        return new ServiceEntry("svc-001", "acme", "orders", "orders",
                "service", "MAVEN", List.of("src/main/java"), List.of("src/test/java"),
                null, true, null, null);
    }

    @Test
    void trigger_withExplicitSha_publishesJobWithCorrectFields() {
        when(registryService.getById("svc-001")).thenReturn(sampleEntry());
        when(db.sql(anyString())).thenReturn(mock(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class,
                RETURNS_DEEP_STUBS));
        when(treeFetcher.fetchJavaPaths("acme", "orders", "abc123"))
                .thenReturn(List.of("src/main/java/Foo.java", "src/main/java/Bar.java"));

        IndexTriggerResponse resp = service.trigger("svc-001",
                new IndexTriggerRequest("abc123"));

        assertThat(resp.commitSha()).isEqualTo("abc123");
        assertThat(resp.fileCount()).isEqualTo(2);
        assertThat(resp.serviceId()).isEqualTo("svc-001");
        assertThat(resp.jobId()).isNotBlank();

        ArgumentCaptor<IngestionJob> jobCaptor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(publisher).publishBatchJob(jobCaptor.capture());
        IngestionJob job = jobCaptor.getValue();
        assertThat(job.jobType()).isEqualTo("MANUAL");
        assertThat(job.changedFiles()).containsExactly(
                "src/main/java/Foo.java", "src/main/java/Bar.java");
    }

    @Test
    void trigger_withNullSha_resolvesHead() {
        when(registryService.getById("svc-001")).thenReturn(sampleEntry());
        when(db.sql(anyString())).thenReturn(mock(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class,
                RETURNS_DEEP_STUBS));
        when(treeFetcher.resolveHeadSha("acme", "orders")).thenReturn("headsha");
        when(treeFetcher.fetchJavaPaths("acme", "orders", "headsha"))
                .thenReturn(List.of("src/main/java/Foo.java"));

        IndexTriggerResponse resp = service.trigger("svc-001", new IndexTriggerRequest(null));

        assertThat(resp.commitSha()).isEqualTo("headsha");
        verify(treeFetcher).resolveHeadSha("acme", "orders");
    }

    @Test
    void trigger_throws409_whenJobAlreadyInFlight() {
        when(registryService.getById("svc-001")).thenReturn(sampleEntry());

        // Mock the in-flight check query to return count = 1
        var statementSpec = mock(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class,
                RETURNS_DEEP_STUBS);
        when(db.sql(contains("IN ('QUEUED', 'RUNNING')"))).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any()).query(Integer.class).single())
                .thenReturn(1);

        assertThatThrownBy(() -> service.trigger("svc-001", new IndexTriggerRequest("abc")))
                .isInstanceOf(JobAlreadyInFlightException.class);

        verify(publisher, never()).publishBatchJob(any());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=IndexTriggerServiceTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — class does not exist.

- [ ] **Step 4: Create `JobAlreadyInFlightException.java`**

```java
package io.testseer.backend.admin;

public class JobAlreadyInFlightException extends RuntimeException {
    public JobAlreadyInFlightException(String serviceId) {
        super("A QUEUED or RUNNING job already exists for service " + serviceId);
    }
}
```

- [ ] **Step 5: Create `IndexTriggerService.java`**

```java
package io.testseer.backend.admin;

import io.testseer.backend.ingestion.AnalysisRunTracker;
import io.testseer.backend.ingestion.GitHubTreeFetcher;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import io.testseer.backend.webhook.KafkaJobPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IndexTriggerService {

    private final ServiceRegistryService registryService;
    private final GitHubTreeFetcher treeFetcher;
    private final KafkaJobPublisher publisher;
    private final AnalysisRunTracker runTracker;
    private final JdbcClient db;

    public IndexTriggerService(ServiceRegistryService registryService,
                                GitHubTreeFetcher treeFetcher,
                                KafkaJobPublisher publisher,
                                AnalysisRunTracker runTracker,
                                JdbcClient db) {
        this.registryService = registryService;
        this.treeFetcher     = treeFetcher;
        this.publisher       = publisher;
        this.runTracker      = runTracker;
        this.db              = db;
    }

    public IndexTriggerResponse trigger(String serviceId, IndexTriggerRequest request) {
        ServiceEntry svc = registryService.getById(serviceId);

        checkNoJobInFlight(serviceId);

        String commitSha = request.commitSha() != null
                ? request.commitSha()
                : treeFetcher.resolveHeadSha(svc.orgId(), svc.repo());

        List<String> javaPaths = treeFetcher.fetchJavaPaths(svc.orgId(), svc.repo(), commitSha);

        IngestionJob job = new IngestionJob(
                UUID.randomUUID().toString(),
                "MANUAL",
                svc.orgId(),
                svc.repo(),
                serviceId,
                commitSha,
                javaPaths,
                null,
                Instant.now(),
                1
        );

        runTracker.markQueued(job);
        publisher.publishBatchJob(job);

        return new IndexTriggerResponse(job.jobId(), serviceId, commitSha, javaPaths.size());
    }

    private void checkNoJobInFlight(String serviceId) {
        int count = db.sql("""
                SELECT COUNT(*) FROM analysis_runs
                WHERE service_id = :svcId AND status IN ('QUEUED', 'RUNNING')
                """)
                .param("svcId", serviceId)
                .query(Integer.class)
                .single();

        if (count > 0) throw new JobAlreadyInFlightException(serviceId);
    }
}
```

- [ ] **Step 6: Fix `WorkerPipeline.java` — treat MANUAL as BASELINE**

Find this line in `WorkerPipeline.java`:

```java
String snapshotType = "NIGHTLY".equals(job.jobType()) ? "BASELINE" : "DELTA";
```

Replace with:

```java
String snapshotType = "NIGHTLY".equals(job.jobType()) || "MANUAL".equals(job.jobType())
        ? "BASELINE" : "DELTA";
```

- [ ] **Step 7: Run tests**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=IndexTriggerServiceTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 8: Compile full project**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/ \
        src/test/java/io/testseer/backend/admin/IndexTriggerServiceTest.java \
        src/main/java/io/testseer/backend/ingestion/WorkerPipeline.java
git commit -m "feat: add IndexTriggerService orchestrating on-demand indexing; MANUAL jobs use BASELINE snapshot"
```

---

### Task 3: `IndexTriggerController` + MockMvc tests

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/IndexTriggerController.java`
- Create: `src/test/java/io/testseer/backend/admin/IndexTriggerControllerTest.java`

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.registry.ServiceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IndexTriggerController.class)
class IndexTriggerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean IndexTriggerService triggerService;

    @Test
    void trigger_returns202_withJobDetails() throws Exception {
        when(triggerService.trigger(eq("svc-001"), any()))
                .thenReturn(new IndexTriggerResponse("job-123", "svc-001", "abc123", 42));

        mockMvc.perform(post("/admin/index/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commitSha\":\"abc123\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.commitSha").value("abc123"))
                .andExpect(jsonPath("$.fileCount").value(42));
    }

    @Test
    void trigger_withNoBody_returns202() throws Exception {
        when(triggerService.trigger(eq("svc-001"), any()))
                .thenReturn(new IndexTriggerResponse("job-456", "svc-001", "headsha", 10));

        mockMvc.perform(post("/admin/index/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-456"));
    }

    @Test
    void trigger_returns404_whenServiceNotFound() throws Exception {
        when(triggerService.trigger(eq("svc-unknown"), any()))
                .thenThrow(new ServiceNotFoundException("svc-unknown"));

        mockMvc.perform(post("/admin/index/svc-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void trigger_returns409_whenJobAlreadyInFlight() throws Exception {
        when(triggerService.trigger(eq("svc-001"), any()))
                .thenThrow(new JobAlreadyInFlightException("svc-001"));

        mockMvc.perform(post("/admin/index/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=IndexTriggerControllerTest -q 2>&1 | tail -10
```

Expected: All 4 FAIL — controller does not exist.

- [ ] **Step 3: Create `IndexTriggerController.java`**

```java
package io.testseer.backend.admin;

import io.testseer.backend.registry.ServiceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/index")
public class IndexTriggerController {

    private final IndexTriggerService triggerService;

    public IndexTriggerController(IndexTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @PostMapping("/{serviceId}")
    public ResponseEntity<IndexTriggerResponse> trigger(
            @PathVariable String serviceId,
            @RequestBody(required = false) IndexTriggerRequest request) {

        IndexTriggerRequest req = request != null ? request : new IndexTriggerRequest(null);
        return ResponseEntity.accepted().body(triggerService.trigger(serviceId, req));
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<Void> handleNotFound(ServiceNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(JobAlreadyInFlightException.class)
    public ResponseEntity<String> handleConflict(JobAlreadyInFlightException ex) {
        return ResponseEntity.status(409).body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=IndexTriggerControllerTest -q 2>&1 | tail -10
```

Expected: All 4 PASS.

- [ ] **Step 5: Run full non-Docker suite**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="GitHubTreeFetcherTest,IndexTriggerServiceTest,IndexTriggerControllerTest,JavaParserOutboundTest,FactExtractorTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/IndexTriggerController.java \
        src/test/java/io/testseer/backend/admin/IndexTriggerControllerTest.java
git commit -m "feat: add POST /admin/index/{serviceId} — on-demand indexing trigger with 409 conflict guard"
```

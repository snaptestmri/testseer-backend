# P10: Local Folder Indexing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /admin/index/local` — index all `.java` files in a server-accessible directory path. Auto-registers the service if it doesn't exist yet. Runs the analysis pipeline synchronously (no Kafka) and returns when indexing is complete.

**Architecture:** `LocalDirectoryFetcher` (new) walks the filesystem path, reads `.java` file content, detects the build tool from `pom.xml`/`build.gradle`, and resolves the git SHA by shelling out to `git rev-parse HEAD` (falls back to `"local-{epoch}"` if not a git repo). `LocalIndexTriggerService` (new) handles auto-registration — it tries `register()`, catches `DuplicateServiceException` and looks up the existing service — then runs the same pipeline stages as `WorkerPipeline` (parse → extract → write) without going through Kafka. `LocalIndexTriggerController` (new) exposes the endpoint. `ServiceRegistryService` (existing) gets one new method: `getByOrgAndName()`.

**Tech Stack:** Spring Boot 3.3, `java.nio.file.Files`, `ProcessBuilder` (git), JdbcClient, MockMvc (unit tests), `@TempDir` (filesystem tests).

**Prerequisite:** P1–P9 complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   ├── admin/
│   │   ├── LocalDirectoryFetcher.java        (new)
│   │   ├── LocalIndexTriggerController.java  (new)
│   │   ├── LocalIndexTriggerService.java     (new)
│   │   ├── LocalIndexTriggerRequest.java     (new — record)
│   │   └── LocalIndexTriggerResponse.java    (new — record)
│   └── registry/
│       └── ServiceRegistryService.java       (modify: add getByOrgAndName)
└── test/java/io/testseer/backend/
    └── admin/
        ├── LocalDirectoryFetcherTest.java
        ├── LocalIndexTriggerServiceTest.java
        └── LocalIndexTriggerControllerTest.java
```

---

### Task 1: `LocalDirectoryFetcher`

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/LocalDirectoryFetcher.java`
- Create: `src/test/java/io/testseer/backend/admin/LocalDirectoryFetcherTest.java`

- [ ] **Step 1: Write failing tests using `@TempDir`**

```java
package io.testseer.backend.admin;

import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDirectoryFetcherTest {

    private final LocalDirectoryFetcher fetcher = new LocalDirectoryFetcher();

    @Test
    void fetchJavaFiles_returnsAllJavaFilesRecursively(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Foo.java"), "class Foo {}");
        Files.writeString(pkg.resolve("Bar.java"), "class Bar {}");
        Files.writeString(pkg.resolve("README.md"), "# readme");

        List<GitHubSourceFetcher.FetchedFile> files =
                fetcher.fetchJavaFiles(tmp.toString());

        assertThat(files).hasSize(2);
        assertThat(files).allMatch(f -> f.path().endsWith(".java"));
        assertThat(files.stream().map(GitHubSourceFetcher.FetchedFile::content))
                .anyMatch(c -> c.contains("class Foo"));
    }

    @Test
    void fetchJavaFiles_throwsIllegalArgument_whenPathNotDirectory(@TempDir Path tmp) {
        assertThatThrownBy(() -> fetcher.fetchJavaFiles(tmp.resolve("nonexistent").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void detectBuildTool_returnsMaven_whenPomXmlPresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        assertThat(fetcher.detectBuildTool(tmp.toString())).isEqualTo("MAVEN");
    }

    @Test
    void detectBuildTool_returnsGradle_whenBuildGradlePresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("build.gradle"), "plugins {}");

        assertThat(fetcher.detectBuildTool(tmp.toString())).isEqualTo("GRADLE");
    }

    @Test
    void detectBuildTool_returnsNull_whenNeitherPresent(@TempDir Path tmp) {
        assertThat(fetcher.detectBuildTool(tmp.toString())).isNull();
    }

    @Test
    void resolveGitSha_returnsFallback_whenNotGitRepo(@TempDir Path tmp) {
        String sha = fetcher.resolveGitSha(tmp.toString());

        // Not a git repo — should return "local-{epoch}", not throw
        assertThat(sha).startsWith("local-");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=LocalDirectoryFetcherTest -q 2>&1 | tail -10
```

Expected: All 6 FAIL — class does not exist.

- [ ] **Step 3: Create `LocalDirectoryFetcher.java`**

```java
package io.testseer.backend.admin;

import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class LocalDirectoryFetcher {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryFetcher.class);

    /**
     * Walks the directory recursively and returns content of every .java file found.
     * Uses the same FetchedFile record as GitHubSourceFetcher so downstream code
     * (JavaParserService) can handle both sources identically.
     */
    public List<GitHubSourceFetcher.FetchedFile> fetchJavaFiles(String path) {
        Path dir = Path.of(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }

        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            String content = Files.readString(p);
                            // Store path relative to the root dir for consistency
                            String relativePath = dir.relativize(p).toString();
                            return new GitHubSourceFetcher.FetchedFile(relativePath, content);
                        } catch (IOException ex) {
                            log.warn("Could not read {}: {}", p, ex.getMessage());
                            return null;
                        }
                    })
                    .filter(f -> f != null)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not walk directory: " + path, ex);
        }
    }

    /**
     * Detects the build tool by looking for pom.xml or build.gradle at the root of the path.
     * Returns "MAVEN", "GRADLE", or null if neither is found.
     */
    public String detectBuildTool(String path) {
        Path dir = Path.of(path);
        if (Files.exists(dir.resolve("pom.xml")))      return "MAVEN";
        if (Files.exists(dir.resolve("build.gradle"))) return "GRADLE";
        return null;
    }

    /**
     * Resolves the HEAD commit SHA by shelling out to git.
     * Returns "local-{epoch}" if the path is not a git repo or git is unavailable.
     */
    public String resolveGitSha(String path) {
        try {
            Process process = new ProcessBuilder("git", "-C", path, "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception ex) {
            log.debug("Could not resolve git SHA for {}: {}", path, ex.getMessage());
        }
        return "local-" + Instant.now().toEpochMilli();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=LocalDirectoryFetcherTest -q 2>&1 | tail -10
```

Expected: All 6 PASS.

- [ ] **Step 5: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/LocalDirectoryFetcher.java \
        src/test/java/io/testseer/backend/admin/LocalDirectoryFetcherTest.java
git commit -m "feat: add LocalDirectoryFetcher for filesystem .java enumeration, build tool detection, and git SHA resolution"
```

---

### Task 2: `ServiceRegistryService` + `LocalIndexTriggerService`

**Files:**
- Modify: `src/main/java/io/testseer/backend/registry/ServiceRegistryService.java`
- Create: `src/main/java/io/testseer/backend/admin/LocalIndexTriggerRequest.java`
- Create: `src/main/java/io/testseer/backend/admin/LocalIndexTriggerResponse.java`
- Create: `src/main/java/io/testseer/backend/admin/LocalIndexTriggerService.java`
- Create: `src/test/java/io/testseer/backend/admin/LocalIndexTriggerServiceTest.java`

- [ ] **Step 1: Add `getByOrgAndName()` to `ServiceRegistryService`**

Add this method to the existing `ServiceRegistryService.java` class (after the existing `getById` method):

```java
    public Optional<ServiceEntry> getByOrgAndName(String orgId, String serviceName) {
        // repo = serviceName by convention (one service per repo)
        return repository.findByOrgRepoService(orgId, serviceName, serviceName);
    }
```

- [ ] **Step 2: Create request and response records**

```java
// src/main/java/io/testseer/backend/admin/LocalIndexTriggerRequest.java
package io.testseer.backend.admin;

import jakarta.validation.constraints.NotBlank;

public record LocalIndexTriggerRequest(
        @NotBlank String orgId,
        @NotBlank String path     // server-accessible filesystem path, e.g. /workspace/orders
) {}
```

```java
// src/main/java/io/testseer/backend/admin/LocalIndexTriggerResponse.java
package io.testseer.backend.admin;

public record LocalIndexTriggerResponse(
        String serviceId,
        String serviceName,
        String commitSha,
        int fileCount,
        boolean autoRegistered   // true if service was newly created
) {}
```

- [ ] **Step 3: Write failing service tests**

```java
package io.testseer.backend.admin;

import io.testseer.backend.ingestion.*;
import io.testseer.backend.registry.*;
import io.testseer.backend.webhook.IngestionJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalIndexTriggerServiceTest {

    @Mock LocalDirectoryFetcher localFetcher;
    @Mock ServiceRegistryService registryService;
    @Mock JavaParserService parserService;
    @Mock FactExtractor factExtractor;
    @Mock PeripheralDetector peripheralDetector;
    @Mock DualWriteService dualWriteService;
    @Mock AnalysisRunTracker runTracker;

    @InjectMocks LocalIndexTriggerService service;

    private ServiceEntry entry(String serviceId) {
        return new ServiceEntry(serviceId, "acme", "orders", "orders",
                "service", "MAVEN", List.of("src/main/java"), List.of("src/test/java"),
                null, true, null, null);
    }

    @Test
    void trigger_autoRegisters_whenServiceNotKnown() {
        when(localFetcher.detectBuildTool("/workspace/orders")).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha("/workspace/orders")).thenReturn("abc123");
        when(localFetcher.fetchJavaFiles("/workspace/orders")).thenReturn(
                List.of(new GitHubSourceFetcher.FetchedFile("Foo.java", "class Foo {}"))
        );
        when(registryService.register(any())).thenReturn(entry("svc-new"));
        when(parserService.parse(any(), any())).thenReturn(
                new ParsedModel("Foo.java", null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), false, null));
        when(factExtractor.extractSymbolFacts(any())).thenReturn(List.of());
        when(factExtractor.extractOutboundCallFacts(any())).thenReturn(List.of());
        when(factExtractor.extractUnsupportedConstructFacts(any())).thenReturn(List.of());
        when(peripheralDetector.detect(any())).thenReturn(List.of());

        LocalIndexTriggerResponse resp = service.trigger(
                new LocalIndexTriggerRequest("acme", "/workspace/orders"));

        assertThat(resp.autoRegistered()).isTrue();
        assertThat(resp.serviceId()).isEqualTo("svc-new");
        assertThat(resp.serviceName()).isEqualTo("orders");
        assertThat(resp.commitSha()).isEqualTo("abc123");
        assertThat(resp.fileCount()).isEqualTo(1);
        verify(dualWriteService).write(any(), any());
        verify(runTracker).markComplete(any());
    }

    @Test
    void trigger_usesExistingService_whenAlreadyRegistered() {
        when(localFetcher.detectBuildTool(any())).thenReturn("MAVEN");
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFiles(any())).thenReturn(List.of());
        when(registryService.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "orders", "orders"));
        when(registryService.getByOrgAndName("acme", "orders"))
                .thenReturn(Optional.of(entry("svc-existing")));
        when(factExtractor.extractSymbolFacts(any())).thenReturn(List.of());
        when(factExtractor.extractOutboundCallFacts(any())).thenReturn(List.of());
        when(factExtractor.extractUnsupportedConstructFacts(any())).thenReturn(List.of());
        when(peripheralDetector.detect(any())).thenReturn(List.of());

        LocalIndexTriggerResponse resp = service.trigger(
                new LocalIndexTriggerRequest("acme", "/workspace/orders"));

        assertThat(resp.autoRegistered()).isFalse();
        assertThat(resp.serviceId()).isEqualTo("svc-existing");
    }

    @Test
    void trigger_marksFailed_andRethrows_onPipelineError() {
        when(localFetcher.detectBuildTool(any())).thenReturn(null);
        when(localFetcher.resolveGitSha(any())).thenReturn("sha1");
        when(localFetcher.fetchJavaFiles(any()))
                .thenThrow(new IllegalArgumentException("not a directory"));
        when(registryService.register(any())).thenReturn(entry("svc-001"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.trigger(new LocalIndexTriggerRequest("acme", "/bad/path")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(runTracker).markFailed(any(), any());
        verify(dualWriteService, never()).write(any(), any());
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=LocalIndexTriggerServiceTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — class does not exist.

- [ ] **Step 5: Create `LocalIndexTriggerService.java`**

```java
package io.testseer.backend.admin;

import io.testseer.backend.ingestion.*;
import io.testseer.backend.registry.*;
import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LocalIndexTriggerService {

    private static final Logger log = LoggerFactory.getLogger(LocalIndexTriggerService.class);

    private final LocalDirectoryFetcher localFetcher;
    private final ServiceRegistryService registryService;
    private final JavaParserService parserService;
    private final FactExtractor factExtractor;
    private final PeripheralDetector peripheralDetector;
    private final DualWriteService dualWriteService;
    private final AnalysisRunTracker runTracker;

    public LocalIndexTriggerService(LocalDirectoryFetcher localFetcher,
                                     ServiceRegistryService registryService,
                                     JavaParserService parserService,
                                     FactExtractor factExtractor,
                                     PeripheralDetector peripheralDetector,
                                     DualWriteService dualWriteService,
                                     AnalysisRunTracker runTracker) {
        this.localFetcher     = localFetcher;
        this.registryService  = registryService;
        this.parserService    = parserService;
        this.factExtractor    = factExtractor;
        this.peripheralDetector = peripheralDetector;
        this.dualWriteService = dualWriteService;
        this.runTracker       = runTracker;
    }

    public LocalIndexTriggerResponse trigger(LocalIndexTriggerRequest request) {
        String serviceName = Path.of(request.path()).getFileName().toString();
        String buildTool   = localFetcher.detectBuildTool(request.path());
        String commitSha   = localFetcher.resolveGitSha(request.path());

        // Find or auto-register the service
        boolean autoRegistered = false;
        ServiceEntry svc;
        try {
            svc = registryService.register(new RegistrationRequest(
                    request.orgId(),
                    serviceName,
                    serviceName,
                    buildTool != null ? buildTool : "UNKNOWN",
                    "service",
                    List.of("src/main/java"),
                    List.of("src/test/java"),
                    null
            ));
            autoRegistered = true;
            log.info("Auto-registered service {}/{}", request.orgId(), serviceName);
        } catch (DuplicateServiceException ex) {
            svc = registryService.getByOrgAndName(request.orgId(), serviceName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Service exists but could not be found: " + serviceName));
        }

        String jobId = UUID.randomUUID().toString();
        IngestionJob job = new IngestionJob(
                jobId, "LOCAL", request.orgId(), serviceName,
                svc.serviceId(), commitSha, List.of(), null, Instant.now(), 1
        );

        runTracker.markQueued(job);
        runTracker.markRunning(jobId);

        try {
            List<GitHubSourceFetcher.FetchedFile> files =
                    localFetcher.fetchJavaFiles(request.path());

            List<ParsedModel> models = files.stream()
                    .map(f -> parserService.parse(f.path(), f.content()))
                    .toList();

            var symbolFacts    = models.stream().flatMap(m -> factExtractor.extractSymbolFacts(m).stream()).toList();
            var outboundFacts  = models.stream().flatMap(m -> factExtractor.extractOutboundCallFacts(m).stream()).toList();
            var peripheralFacts = models.stream().flatMap(m -> peripheralDetector.detect(m).stream()).toList();
            var unsupported    = models.stream().flatMap(m -> factExtractor.extractUnsupportedConstructFacts(m).stream()).toList();

            FactBatch batch = new FactBatch(
                    jobId, request.orgId(), serviceName, svc.serviceId(),
                    commitSha, "BASELINE",
                    symbolFacts, outboundFacts, peripheralFacts, unsupported
            );

            dualWriteService.write(batch, models);
            runTracker.markComplete(jobId);

            log.info("Local index complete for {}: {} files, {} symbol facts",
                    serviceName, files.size(), symbolFacts.size());

            return new LocalIndexTriggerResponse(
                    svc.serviceId(), serviceName, commitSha, files.size(), autoRegistered);

        } catch (Exception ex) {
            runTracker.markFailed(jobId, ex.getMessage());
            throw ex;
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=LocalIndexTriggerServiceTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 7: Compile full project**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/registry/ServiceRegistryService.java \
        src/main/java/io/testseer/backend/admin/LocalIndexTriggerRequest.java \
        src/main/java/io/testseer/backend/admin/LocalIndexTriggerResponse.java \
        src/main/java/io/testseer/backend/admin/LocalIndexTriggerService.java \
        src/test/java/io/testseer/backend/admin/LocalIndexTriggerServiceTest.java
git commit -m "feat: add LocalIndexTriggerService — synchronous local folder indexing with auto-registration"
```

---

### Task 3: `LocalIndexTriggerController` + MockMvc tests

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/LocalIndexTriggerController.java`
- Create: `src/test/java/io/testseer/backend/admin/LocalIndexTriggerControllerTest.java`

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LocalIndexTriggerController.class)
class LocalIndexTriggerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean LocalIndexTriggerService triggerService;

    @Test
    void trigger_returns200_withIndexResult() throws Exception {
        when(triggerService.trigger(any())).thenReturn(
                new LocalIndexTriggerResponse("svc-001", "orders", "abc123", 42, true));

        mockMvc.perform(post("/admin/index/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"acme\",\"path\":\"/workspace/orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("svc-001"))
                .andExpect(jsonPath("$.serviceName").value("orders"))
                .andExpect(jsonPath("$.fileCount").value(42))
                .andExpect(jsonPath("$.autoRegistered").value(true));
    }

    @Test
    void trigger_returns400_whenPathInvalid() throws Exception {
        when(triggerService.trigger(any()))
                .thenThrow(new IllegalArgumentException("Path is not a directory: /bad"));

        mockMvc.perform(post("/admin/index/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"acme\",\"path\":\"/bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not a directory")));
    }

    @Test
    void trigger_returns400_whenBodyMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/admin/index/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"\",\"path\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=LocalIndexTriggerControllerTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — controller does not exist.

- [ ] **Step 3: Create `LocalIndexTriggerController.java`**

```java
package io.testseer.backend.admin;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/index/local")
public class LocalIndexTriggerController {

    private final LocalIndexTriggerService triggerService;

    public LocalIndexTriggerController(LocalIndexTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @PostMapping
    public LocalIndexTriggerResponse trigger(
            @Valid @RequestBody LocalIndexTriggerRequest request) {
        return triggerService.trigger(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadPath(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=LocalIndexTriggerControllerTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 5: Run full non-Docker suite**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="LocalDirectoryFetcherTest,LocalIndexTriggerServiceTest,LocalIndexTriggerControllerTest,DiscoveryControllerTest,IndexTriggerControllerTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/LocalIndexTriggerController.java \
        src/test/java/io/testseer/backend/admin/LocalIndexTriggerControllerTest.java
git commit -m "feat: add POST /admin/index/local — index server-accessible folder with auto-registration"
```

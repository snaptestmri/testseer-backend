# P9: Org-Level Service Discovery

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /admin/discover?orgId=acme` — server-side scan that auto-registers all Java services in a GitHub org. No local checkouts, no per-repo developer action. Returns a summary of what was registered, what was already known, and what was skipped (non-Java repos).

**Architecture:** `GitHubOrgScanner` calls GitHub's org repos API (paginated) and checks each repo for a `pom.xml` or `build.gradle` at root to detect Java projects. `DiscoveryService` coordinates: scan → dedup against `service_registry` → register new ones → return `DiscoveryResult`. `DiscoveryController` exposes the endpoint.

**Tech Stack:** Spring Boot 3.3, Spring Web (RestClient), `ServiceRegistryService` (existing), MockMvc (unit tests).

**Prerequisite:** P1–P8 complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   └── admin/
│       ├── DiscoveryController.java      (new — POST /admin/discover)
│       ├── DiscoveryService.java         (new — orchestration)
│       ├── DiscoveryResult.java          (new — response record)
│       └── GitHubOrgScanner.java         (new — GitHub org/repo API client)
└── test/java/io/testseer/backend/
    └── admin/
        ├── DiscoveryControllerTest.java
        ├── DiscoveryServiceTest.java
        └── GitHubOrgScannerTest.java
```

---

### Task 1: `GitHubOrgScanner` — repo listing and Java detection

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/GitHubOrgScanner.java`
- Create: `src/test/java/io/testseer/backend/admin/GitHubOrgScannerTest.java`

`GitHubOrgScanner` makes three types of GitHub API calls:
1. `GET /orgs/{orgId}/repos?per_page=100&type=source` — paginated repo list
2. `GET /repos/{orgId}/{repo}/contents/pom.xml` — 200 = Maven, 404 = not Maven
3. `GET /repos/{orgId}/{repo}/contents/build.gradle` — 200 = Gradle, 404 = not Gradle

Pagination: GitHub includes a `Link` header when more pages exist:
`<https://api.github.com/orgs/acme/repos?page=2>; rel="next"`
Parse to determine if there is a next page.

Returns a `List<DetectedRepo>` where:
```java
record DetectedRepo(String name, String buildTool, String defaultBranch) {}
```

- [ ] **Step 1: Write failing tests**

```java
package io.testseer.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class GitHubOrgScannerTest {

    @Test
    void scanJavaRepos_detectsMavenRepo() {
        RestClient.ResponseSpec listSpec  = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        RestClient.ResponseSpec mavenSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        RestClient restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any())).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);

        // Repo list — one repo, no pagination
        when(uriSpec.retrieve()).thenReturn(listSpec);
        HttpHeaders noLink = new HttpHeaders();
        when(listSpec.toEntity(List.class)).thenReturn(
                org.springframework.http.ResponseEntity.ok()
                        .headers(noLink)
                        .body(List.of(Map.of(
                                "name", "orders",
                                "default_branch", "main",
                                "archived", false,
                                "fork", false
                        )))
        );

        // pom.xml check — 200
        when(listSpec.body(Map.class)).thenReturn(Map.of("name", "pom.xml"));

        GitHubOrgScanner scanner = new GitHubOrgScanner(restClient);
        List<GitHubOrgScanner.DetectedRepo> repos = scanner.scanJavaRepos("acme");

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).name()).isEqualTo("orders");
        assertThat(repos.get(0).buildTool()).isEqualTo("MAVEN");
        assertThat(repos.get(0).defaultBranch()).isEqualTo("main");
    }

    @Test
    void scanJavaRepos_skipsNonJavaRepo() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);

        // Repo list returns one non-Java repo
        HttpHeaders noLink = new HttpHeaders();
        when(restClient.get().uri(anyString(), any()).retrieve()
                .toEntity(List.class))
                .thenReturn(org.springframework.http.ResponseEntity.ok()
                        .headers(noLink)
                        .body(List.of(Map.of(
                                "name", "frontend",
                                "default_branch", "main",
                                "archived", false,
                                "fork", false
                        )))
                );

        // Both pom.xml and build.gradle checks return null (not found)
        when(restClient.get().uri(anyString(), any(), any(), any()).retrieve().body(Map.class))
                .thenReturn(null);

        GitHubOrgScanner scanner = new GitHubOrgScanner(restClient);
        List<GitHubOrgScanner.DetectedRepo> repos = scanner.scanJavaRepos("acme");

        assertThat(repos).isEmpty();
    }

    @Test
    void scanJavaRepos_skipsArchivedAndForks() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);

        HttpHeaders noLink = new HttpHeaders();
        when(restClient.get().uri(anyString(), any()).retrieve()
                .toEntity(List.class))
                .thenReturn(org.springframework.http.ResponseEntity.ok()
                        .headers(noLink)
                        .body(List.of(
                                Map.of("name", "archived-svc", "default_branch", "main",
                                        "archived", true,  "fork", false),
                                Map.of("name", "forked-svc",   "default_branch", "main",
                                        "archived", false, "fork", true)
                        ))
                );

        GitHubOrgScanner scanner = new GitHubOrgScanner(restClient);
        List<GitHubOrgScanner.DetectedRepo> repos = scanner.scanJavaRepos("acme");

        assertThat(repos).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GitHubOrgScannerTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — class does not exist.

- [ ] **Step 3: Create `GitHubOrgScanner.java`**

```java
package io.testseer.backend.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GitHubOrgScanner {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrgScanner.class);

    private final RestClient restClient;

    public GitHubOrgScanner(
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
    GitHubOrgScanner(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Lists all non-archived, non-fork repos in the org and returns those
     * that contain a pom.xml (MAVEN) or build.gradle (GRADLE) at the root.
     * Skips repos that have neither — they are not Java services.
     */
    @SuppressWarnings("unchecked")
    public List<DetectedRepo> scanJavaRepos(String orgId) {
        List<Map<String, Object>> allRepos = listAllRepos(orgId);
        List<DetectedRepo> result = new ArrayList<>();

        for (Map<String, Object> repo : allRepos) {
            String name          = (String) repo.get("name");
            String defaultBranch = (String) repo.get("default_branch");
            boolean archived     = Boolean.TRUE.equals(repo.get("archived"));
            boolean fork         = Boolean.TRUE.equals(repo.get("fork"));

            if (archived || fork) {
                log.debug("Skipping {}/{} (archived={}, fork={})", orgId, name, archived, fork);
                continue;
            }

            String buildTool = detectBuildTool(orgId, name);
            if (buildTool == null) {
                log.debug("Skipping {}/{} — no pom.xml or build.gradle found", orgId, name);
                continue;
            }

            result.add(new DetectedRepo(name, buildTool, defaultBranch));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAllRepos(String orgId) {
        List<Map<String, Object>> all = new ArrayList<>();
        String uri = "/orgs/{org}/repos?per_page=100&type=source";

        while (uri != null) {
            ResponseEntity<List> response = restClient.get()
                    .uri(uri, orgId)
                    .retrieve()
                    .toEntity(List.class);

            List<Map<String, Object>> page = response.getBody();
            if (page != null) all.addAll(page);

            uri = extractNextPageUri(response.getHeaders().getFirst("Link"));
        }

        return all;
    }

    private String detectBuildTool(String orgId, String repo) {
        if (fileExists(orgId, repo, "pom.xml"))      return "MAVEN";
        if (fileExists(orgId, repo, "build.gradle")) return "GRADLE";
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean fileExists(String orgId, String repo, String filename) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/repos/{org}/{repo}/contents/{file}", orgId, repo, filename)
                    .retrieve()
                    .body(Map.class);
            return response != null;
        } catch (HttpClientErrorException.NotFound ex) {
            return false;
        } catch (Exception ex) {
            log.warn("Could not check {}/{}/{}: {}", orgId, repo, filename, ex.getMessage());
            return false;
        }
    }

    /**
     * Parses GitHub's Link header to extract the "next" page URL.
     * Format: <https://api.github.com/orgs/acme/repos?page=2>; rel="next"
     * Returns null if no "next" link exists (last page).
     */
    static String extractNextPageUri(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return null;
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<') + 1;
                int end   = part.indexOf('>');
                if (start > 0 && end > start) {
                    // Strip the base URL — RestClient already has it as baseUrl
                    String full = part.substring(start, end).trim();
                    return full.replaceFirst("https://api\\.github\\.com", "");
                }
            }
        }
        return null;
    }

    public record DetectedRepo(String name, String buildTool, String defaultBranch) {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=GitHubOrgScannerTest -q 2>&1 | tail -10
```

Expected: All 3 PASS. If mock interaction mismatches occur, adjust the mock setup — the core logic is what matters, not exact mock wiring.

- [ ] **Step 5: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/GitHubOrgScanner.java \
        src/test/java/io/testseer/backend/admin/GitHubOrgScannerTest.java
git commit -m "feat: add GitHubOrgScanner for paginated org repo listing and Java build tool detection"
```

---

### Task 2: `DiscoveryService` and `DiscoveryResult`

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/DiscoveryResult.java`
- Create: `src/main/java/io/testseer/backend/admin/DiscoveryService.java`
- Create: `src/test/java/io/testseer/backend/admin/DiscoveryServiceTest.java`

- [ ] **Step 1: Create `DiscoveryResult.java`**

```java
package io.testseer.backend.admin;

import java.util.List;

public record DiscoveryResult(
        List<String> registered,    // service names newly registered
        List<String> alreadyKnown,  // service names already in registry
        List<String> skipped        // repo names with no Java build file
) {
    public int total() {
        return registered.size() + alreadyKnown.size() + skipped.size();
    }
}
```

- [ ] **Step 2: Write failing service tests**

```java
package io.testseer.backend.admin;

import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    @Mock GitHubOrgScanner scanner;
    @Mock ServiceRegistryService registryService;

    @InjectMocks DiscoveryService service;

    @Test
    void discover_registersNewRepos_andSkipsNonJava() {
        when(scanner.scanJavaRepos("acme")).thenReturn(List.of(
                new GitHubOrgScanner.DetectedRepo("orders",   "MAVEN",  "main"),
                new GitHubOrgScanner.DetectedRepo("payments", "GRADLE", "main")
        ));
        when(registryService.register(any())).thenReturn(
                mock(ServiceEntry.class), mock(ServiceEntry.class));

        DiscoveryResult result = service.discover("acme");

        assertThat(result.registered()).containsExactlyInAnyOrder("orders", "payments");
        assertThat(result.alreadyKnown()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        verify(registryService, times(2)).register(any());
    }

    @Test
    void discover_placesAlreadyRegisteredInKnownList() {
        when(scanner.scanJavaRepos("acme")).thenReturn(List.of(
                new GitHubOrgScanner.DetectedRepo("orders", "MAVEN", "main")
        ));
        when(registryService.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "orders", "orders"));

        DiscoveryResult result = service.discover("acme");

        assertThat(result.registered()).isEmpty();
        assertThat(result.alreadyKnown()).containsExactly("orders");
    }

    @Test
    void discover_emptyOrg_returnsEmptyResult() {
        when(scanner.scanJavaRepos("acme")).thenReturn(List.of());

        DiscoveryResult result = service.discover("acme");

        assertThat(result.registered()).isEmpty();
        assertThat(result.alreadyKnown()).isEmpty();
        assertThat(result.total()).isEqualTo(0);
        verifyNoInteractions(registryService);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=DiscoveryServiceTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — `DiscoveryService` does not exist.

- [ ] **Step 4: Create `DiscoveryService.java`**

```java
package io.testseer.backend.admin;

import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    private final GitHubOrgScanner scanner;
    private final ServiceRegistryService registryService;

    public DiscoveryService(GitHubOrgScanner scanner,
                             ServiceRegistryService registryService) {
        this.scanner         = scanner;
        this.registryService = registryService;
    }

    public DiscoveryResult discover(String orgId) {
        List<GitHubOrgScanner.DetectedRepo> detected = scanner.scanJavaRepos(orgId);

        List<String> registered   = new ArrayList<>();
        List<String> alreadyKnown = new ArrayList<>();
        List<String> skipped      = new ArrayList<>();

        for (GitHubOrgScanner.DetectedRepo repo : detected) {
            try {
                registryService.register(new RegistrationRequest(
                        orgId,
                        repo.name(),
                        repo.name(),
                        repo.buildTool(),
                        "service",
                        List.of("src/main/java"),
                        List.of("src/test/java"),
                        null
                ));
                registered.add(repo.name());
                log.info("Registered {}/{}", orgId, repo.name());
            } catch (DuplicateServiceException ex) {
                alreadyKnown.add(repo.name());
                log.debug("Already registered: {}/{}", orgId, repo.name());
            }
        }

        return new DiscoveryResult(registered, alreadyKnown, skipped);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=DiscoveryServiceTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/DiscoveryResult.java \
        src/main/java/io/testseer/backend/admin/DiscoveryService.java \
        src/test/java/io/testseer/backend/admin/DiscoveryServiceTest.java
git commit -m "feat: add DiscoveryService auto-registering Java repos found by GitHubOrgScanner"
```

---

### Task 3: `DiscoveryController` + MockMvc tests

**Files:**
- Create: `src/main/java/io/testseer/backend/admin/DiscoveryController.java`
- Create: `src/test/java/io/testseer/backend/admin/DiscoveryControllerTest.java`

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiscoveryController.class)
class DiscoveryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean DiscoveryService discoveryService;

    @Test
    void discover_returns200_withCounts() throws Exception {
        when(discoveryService.discover("acme")).thenReturn(
                new DiscoveryResult(
                        List.of("orders", "payments"),
                        List.of("billing"),
                        List.of("frontend")
                ));

        mockMvc.perform(post("/admin/discover").param("orgId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered.length()").value(2))
                .andExpect(jsonPath("$.alreadyKnown[0]").value("billing"))
                .andExpect(jsonPath("$.skipped[0]").value("frontend"));
    }

    @Test
    void discover_missingOrgId_returns400() throws Exception {
        mockMvc.perform(post("/admin/discover"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discover_emptyOrg_returns200_withEmptyLists() throws Exception {
        when(discoveryService.discover("empty-org")).thenReturn(
                new DiscoveryResult(List.of(), List.of(), List.of()));

        mockMvc.perform(post("/admin/discover").param("orgId", "empty-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered.length()").value(0));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=DiscoveryControllerTest -q 2>&1 | tail -10
```

Expected: All 3 FAIL — controller does not exist.

- [ ] **Step 3: Create `DiscoveryController.java`**

```java
package io.testseer.backend.admin;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/discover")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @PostMapping
    public DiscoveryResult discover(@RequestParam String orgId) {
        return discoveryService.discover(orgId);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest=DiscoveryControllerTest -q 2>&1 | tail -10
```

Expected: All 3 PASS.

- [ ] **Step 5: Run full non-Docker suite**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
mvn test -Dtest="GitHubOrgScannerTest,DiscoveryServiceTest,DiscoveryControllerTest,IndexTriggerControllerTest,IndexTriggerServiceTest,GitHubTreeFetcherTest" -q 2>&1 | tail -10
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd "/Users/mthigale/Documents/Claude/Projects/Test Planning/testseer-backend"
git add src/main/java/io/testseer/backend/admin/DiscoveryController.java \
        src/test/java/io/testseer/backend/admin/DiscoveryControllerTest.java
git commit -m "feat: add POST /admin/discover?orgId= for server-side org-level service registration"
```

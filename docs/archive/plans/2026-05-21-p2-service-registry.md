# P2: Service Registry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the REST CRUD API for the Service Registry — register, list, get, update, and disable services, with field-level validation, duplicate detection, and soft-delete semantics.

**Architecture:** `ServiceRegistryController` handles HTTP; `ServiceRegistryService` owns validation and business logic; `ServiceRegistryRepository` owns all JDBC. Registration requires `org_id`, `repo`, `service_name`, `build_tool`; duplicates return 409; missing fields return 400 with field-level messages; disable sets `enabled = false` without deleting facts.

**Tech Stack:** Spring Boot 3.3, JdbcClient (Spring 6.1), Bean Validation, MockMvc (unit tests), Testcontainers PostgreSQL (integration tests)

**Prerequisite:** P1 complete — `service_registry` table exists via Flyway.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   └── registry/
│       ├── ServiceEntry.java              (domain record)
│       ├── RegistrationRequest.java       (POST request DTO)
│       ├── RegistryUpdateRequest.java     (PATCH request DTO)
│       ├── ServiceRegistryRepository.java (JDBC)
│       ├── ServiceRegistryService.java    (validation + logic)
│       └── ServiceRegistryController.java (REST)
└── test/java/io/testseer/backend/
    └── registry/
        ├── ServiceRegistryControllerTest.java   (MockMvc unit test)
        └── ServiceRegistryIntegrationTest.java  (Testcontainers)
```

---

### Task 1: Domain records and DTOs

**Files:**
- Create: `src/main/java/io/testseer/backend/registry/ServiceEntry.java`
- Create: `src/main/java/io/testseer/backend/registry/RegistrationRequest.java`
- Create: `src/main/java/io/testseer/backend/registry/RegistryUpdateRequest.java`

- [ ] **Step 1: Create `ServiceEntry.java`**

```java
package io.testseer.backend.registry;

import java.time.Instant;
import java.util.List;

public record ServiceEntry(
        String serviceId,
        String orgId,
        String repo,
        String serviceName,
        String moduleType,
        String buildTool,
        List<String> sourceRoots,
        List<String> testRoots,
        String ownerTeam,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {}
```

- [ ] **Step 2: Create `RegistrationRequest.java`**

```java
package io.testseer.backend.registry;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RegistrationRequest(
        @NotBlank String orgId,
        @NotBlank String repo,
        @NotBlank String serviceName,
        @NotBlank String buildTool,
        String moduleType,
        List<String> sourceRoots,
        List<String> testRoots,
        String ownerTeam
) {}
```

- [ ] **Step 3: Create `RegistryUpdateRequest.java`**

```java
package io.testseer.backend.registry;

import java.util.List;

public record RegistryUpdateRequest(
        Boolean enabled,
        List<String> sourceRoots,
        List<String> testRoots,
        String ownerTeam
) {}
```

- [ ] **Step 4: Verify compilation**

```bash
mvn compile -pl . -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/registry/
git commit -m "feat: add ServiceEntry record and request DTOs for service registry"
```

---

### Task 2: `ServiceRegistryRepository`

**Files:**
- Create: `src/main/java/io/testseer/backend/registry/ServiceRegistryRepository.java`
- Create: `src/test/java/io/testseer/backend/registry/ServiceRegistryIntegrationTest.java`

- [ ] **Step 1: Write failing integration test for `findById`**

```java
package io.testseer.backend.registry;

import io.testseer.backend.DatabaseMigrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class ServiceRegistryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7");

    @Autowired
    ServiceRegistryRepository repository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void cleanup() {
        jdbcClient.sql("DELETE FROM service_registry").update();
    }

    @Test
    void saveAndFindById() {
        var entry = new ServiceEntry(
                "svc-001", "acme", "order-service", "orders",
                "service", "MAVEN",
                java.util.List.of("src/main/java"),
                java.util.List.of("src/test/java"),
                "platform", true, null, null
        );

        repository.save(entry);

        Optional<ServiceEntry> found = repository.findById("svc-001");

        assertThat(found).isPresent();
        assertThat(found.get().orgId()).isEqualTo("acme");
        assertThat(found.get().serviceName()).isEqualTo("orders");
        assertThat(found.get().enabled()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=ServiceRegistryIntegrationTest#saveAndFindById -q
```

Expected: FAIL — `ServiceRegistryRepository` does not exist.

- [ ] **Step 3: Create `ServiceRegistryRepository.java`**

```java
package io.testseer.backend.registry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class ServiceRegistryRepository {

    private final JdbcClient db;

    public ServiceRegistryRepository(JdbcClient db) {
        this.db = db;
    }

    public void save(ServiceEntry e) {
        db.sql("""
                INSERT INTO service_registry
                  (service_id, org_id, repo, service_name, module_type, build_tool,
                   source_roots, test_roots, owner_team, enabled)
                VALUES (:serviceId, :orgId, :repo, :serviceName, :moduleType, :buildTool,
                        :sourceRoots::text[], :testRoots::text[], :ownerTeam, :enabled)
                ON CONFLICT (service_id) DO UPDATE SET
                  enabled      = EXCLUDED.enabled,
                  source_roots = EXCLUDED.source_roots,
                  test_roots   = EXCLUDED.test_roots,
                  owner_team   = EXCLUDED.owner_team,
                  updated_at   = now()
                """)
                .param("serviceId",   e.serviceId())
                .param("orgId",       e.orgId())
                .param("repo",        e.repo())
                .param("serviceName", e.serviceName())
                .param("moduleType",  e.moduleType() != null ? e.moduleType() : "service")
                .param("buildTool",   e.buildTool())
                .param("sourceRoots", toArrayLiteral(e.sourceRoots()))
                .param("testRoots",   toArrayLiteral(e.testRoots()))
                .param("ownerTeam",   e.ownerTeam())
                .param("enabled",     e.enabled())
                .update();
    }

    public Optional<ServiceEntry> findById(String serviceId) {
        return db.sql("SELECT * FROM service_registry WHERE service_id = :id")
                .param("id", serviceId)
                .query(this::mapRow)
                .optional();
    }

    public Optional<ServiceEntry> findByOrgRepoService(String orgId, String repo, String serviceName) {
        return db.sql("""
                SELECT * FROM service_registry
                WHERE org_id = :orgId AND repo = :repo AND service_name = :serviceName
                """)
                .param("orgId",       orgId)
                .param("repo",        repo)
                .param("serviceName", serviceName)
                .query(this::mapRow)
                .optional();
    }

    public List<ServiceEntry> findAll() {
        return db.sql("SELECT * FROM service_registry ORDER BY org_id, repo, service_name")
                .query(this::mapRow)
                .list();
    }

    public int disable(String serviceId) {
        return db.sql("UPDATE service_registry SET enabled = false, updated_at = now() WHERE service_id = :id")
                .param("id", serviceId)
                .update();
    }

    public int updateFields(String serviceId, RegistryUpdateRequest req) {
        return db.sql("""
                UPDATE service_registry SET
                  enabled      = COALESCE(:enabled, enabled),
                  source_roots = COALESCE(:sourceRoots::text[], source_roots),
                  test_roots   = COALESCE(:testRoots::text[], test_roots),
                  owner_team   = COALESCE(:ownerTeam, owner_team),
                  updated_at   = now()
                WHERE service_id = :id
                """)
                .param("enabled",     req.enabled())
                .param("sourceRoots", req.sourceRoots() != null ? toArrayLiteral(req.sourceRoots()) : null)
                .param("testRoots",   req.testRoots() != null ? toArrayLiteral(req.testRoots()) : null)
                .param("ownerTeam",   req.ownerTeam())
                .param("id",          serviceId)
                .update();
    }

    private ServiceEntry mapRow(ResultSet rs, int row) throws SQLException {
        return new ServiceEntry(
                rs.getString("service_id"),
                rs.getString("org_id"),
                rs.getString("repo"),
                rs.getString("service_name"),
                rs.getString("module_type"),
                rs.getString("build_tool"),
                arrayToList(rs.getArray("source_roots")),
                arrayToList(rs.getArray("test_roots")),
                rs.getString("owner_team"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static String toArrayLiteral(List<String> items) {
        if (items == null || items.isEmpty()) return "{src/main/java}";
        return "{" + String.join(",", items) + "}";
    }

    private static List<String> arrayToList(Array arr) throws SQLException {
        if (arr == null) return List.of();
        return Arrays.asList((String[]) arr.getArray());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=ServiceRegistryIntegrationTest#saveAndFindById -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/registry/ServiceRegistryRepository.java \
        src/test/java/io/testseer/backend/registry/ServiceRegistryIntegrationTest.java
git commit -m "feat: add ServiceRegistryRepository with JDBC CRUD operations"
```

---

### Task 3: `ServiceRegistryService` — validation and business logic

**Files:**
- Create: `src/main/java/io/testseer/backend/registry/ServiceRegistryService.java`
- Modify: `src/test/java/io/testseer/backend/registry/ServiceRegistryIntegrationTest.java`

- [ ] **Step 1: Add failing tests for duplicate detection**

Add to `ServiceRegistryIntegrationTest`:

```java
@Autowired
ServiceRegistryService service;

@Test
void registerService_returnsServiceEntry() {
    var req = new RegistrationRequest(
            "acme", "order-service", "orders", "MAVEN",
            "service", List.of("src/main/java"), List.of("src/test/java"), "platform"
    );

    ServiceEntry result = service.register(req);

    assertThat(result.orgId()).isEqualTo("acme");
    assertThat(result.repo()).isEqualTo("order-service");
    assertThat(result.enabled()).isTrue();
    assertThat(result.serviceId()).isNotBlank();
}

@Test
void register_throwsDuplicateException_whenSameOrgRepoService() {
    var req = new RegistrationRequest(
            "acme", "order-service", "orders", "MAVEN",
            null, null, null, null
    );
    service.register(req);

    org.junit.jupiter.api.Assertions.assertThrows(
            DuplicateServiceException.class,
            () -> service.register(req)
    );
}

@Test
void disable_setsEnabledFalse_withoutDeletingEntry() {
    var req = new RegistrationRequest(
            "acme", "order-service", "orders", "MAVEN",
            null, null, null, null
    );
    ServiceEntry entry = service.register(req);

    service.disable(entry.serviceId());

    Optional<ServiceEntry> found = repository.findById(entry.serviceId());
    assertThat(found).isPresent();
    assertThat(found.get().enabled()).isFalse();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest="ServiceRegistryIntegrationTest#registerService_returnsServiceEntry+register_throwsDuplicateException_whenSameOrgRepoService+disable_setsEnabledFalse_withoutDeletingEntry" -q
```

Expected: All three FAIL — `ServiceRegistryService` and `DuplicateServiceException` do not exist.

- [ ] **Step 3: Create `DuplicateServiceException.java`**

```java
package io.testseer.backend.registry;

public class DuplicateServiceException extends RuntimeException {
    public DuplicateServiceException(String orgId, String repo, String serviceName) {
        super("Service already registered: %s/%s/%s".formatted(orgId, repo, serviceName));
    }
}
```

- [ ] **Step 4: Create `ServiceRegistryService.java`**

```java
package io.testseer.backend.registry;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ServiceRegistryService {

    private final ServiceRegistryRepository repository;

    public ServiceRegistryService(ServiceRegistryRepository repository) {
        this.repository = repository;
    }

    public ServiceEntry register(RegistrationRequest req) {
        repository.findByOrgRepoService(req.orgId(), req.repo(), req.serviceName())
                .ifPresent(existing -> {
                    throw new DuplicateServiceException(req.orgId(), req.repo(), req.serviceName());
                });

        var entry = new ServiceEntry(
                UUID.randomUUID().toString(),
                req.orgId(),
                req.repo(),
                req.serviceName(),
                req.moduleType() != null ? req.moduleType() : "service",
                req.buildTool(),
                req.sourceRoots() != null ? req.sourceRoots() : List.of("src/main/java"),
                req.testRoots()   != null ? req.testRoots()   : List.of("src/test/java"),
                req.ownerTeam(),
                true,
                null,
                null
        );
        repository.save(entry);
        return repository.findById(entry.serviceId()).orElseThrow();
    }

    public ServiceEntry getById(String serviceId) {
        return repository.findById(serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    public List<ServiceEntry> listAll() {
        return repository.findAll();
    }

    public ServiceEntry update(String serviceId, RegistryUpdateRequest req) {
        if (repository.updateFields(serviceId, req) == 0) {
            throw new ServiceNotFoundException(serviceId);
        }
        return repository.findById(serviceId).orElseThrow();
    }

    public void disable(String serviceId) {
        if (repository.disable(serviceId) == 0) {
            throw new ServiceNotFoundException(serviceId);
        }
    }
}
```

- [ ] **Step 5: Create `ServiceNotFoundException.java`**

```java
package io.testseer.backend.registry;

public class ServiceNotFoundException extends RuntimeException {
    public ServiceNotFoundException(String serviceId) {
        super("Service not found: " + serviceId);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
mvn test -Dtest="ServiceRegistryIntegrationTest#registerService_returnsServiceEntry+register_throwsDuplicateException_whenSameOrgRepoService+disable_setsEnabledFalse_withoutDeletingEntry" -q
```

Expected: All three PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/testseer/backend/registry/
git commit -m "feat: add ServiceRegistryService with register, disable, and duplicate detection"
```

---

### Task 4: `ServiceRegistryController` and error handling

**Files:**
- Create: `src/main/java/io/testseer/backend/registry/ServiceRegistryController.java`
- Create: `src/main/java/io/testseer/backend/registry/RegistryExceptionHandler.java`
- Create: `src/test/java/io/testseer/backend/registry/ServiceRegistryControllerTest.java`

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceRegistryController.class)
class ServiceRegistryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    ServiceRegistryService service;

    private static ServiceEntry sampleEntry() {
        return new ServiceEntry(
                "svc-001", "acme", "order-service", "orders",
                "service", "MAVEN",
                List.of("src/main/java"), List.of("src/test/java"),
                "platform", true, Instant.now(), Instant.now()
        );
    }

    @Test
    void POST_registry_returns201_withLocation() throws Exception {
        when(service.register(any())).thenReturn(sampleEntry());

        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orgId":"acme","repo":"order-service",
                             "serviceName":"orders","buildTool":"MAVEN"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/registry/services/svc-001"))
                .andExpect(jsonPath("$.serviceId").value("svc-001"));
    }

    @Test
    void POST_registry_returns400_whenRequiredFieldMissing() throws Exception {
        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orgId":"acme","repo":"order-service"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void POST_registry_returns409_whenDuplicate() throws Exception {
        when(service.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "order-service", "orders"));

        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orgId":"acme","repo":"order-service",
                             "serviceName":"orders","buildTool":"MAVEN"}
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SERVICE"));
    }

    @Test
    void GET_registry_byId_returns404_whenNotFound() throws Exception {
        when(service.getById("missing"))
                .thenThrow(new ServiceNotFoundException("missing"));

        mockMvc.perform(get("/registry/services/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void PATCH_registry_disables_service() throws Exception {
        when(service.update(any(), any())).thenReturn(sampleEntry());

        mockMvc.perform(patch("/registry/services/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"enabled":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("svc-001"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=ServiceRegistryControllerTest -q
```

Expected: All 5 FAIL — controller does not exist.

- [ ] **Step 3: Create `ServiceRegistryController.java`**

```java
package io.testseer.backend.registry;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/registry/services")
public class ServiceRegistryController {

    private final ServiceRegistryService service;

    public ServiceRegistryController(ServiceRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ServiceEntry> register(@Valid @RequestBody RegistrationRequest req) {
        ServiceEntry entry = service.register(req);
        return ResponseEntity
                .created(URI.create("/registry/services/" + entry.serviceId()))
                .body(entry);
    }

    @GetMapping
    public List<ServiceEntry> listAll() {
        return service.listAll();
    }

    @GetMapping("/{serviceId}")
    public ServiceEntry getById(@PathVariable String serviceId) {
        return service.getById(serviceId);
    }

    @PatchMapping("/{serviceId}")
    public ServiceEntry update(@PathVariable String serviceId,
                               @RequestBody RegistryUpdateRequest req) {
        return service.update(serviceId, req);
    }

    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> disable(@PathVariable String serviceId) {
        service.disable(serviceId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Create `RegistryExceptionHandler.java`**

```java
package io.testseer.backend.registry;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class RegistryExceptionHandler {

    @ExceptionHandler(DuplicateServiceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateServiceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "DUPLICATE_SERVICE", "message", ex.getMessage()));
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ServiceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "NOT_FOUND",
                        "message", ex.getMessage(),
                        "hint", "Register via POST /registry/services"
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        return ResponseEntity.badRequest()
                .body(Map.of("error", "VALIDATION_ERROR", "errors", errors));
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -Dtest=ServiceRegistryControllerTest -q
```

Expected: All 5 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/testseer/backend/registry/
git commit -m "feat: add ServiceRegistryController with 400/404/409 error handling"
```

---

### Task 5: Full integration test for registry API

**Files:**
- Modify: `src/test/java/io/testseer/backend/registry/ServiceRegistryIntegrationTest.java`

- [ ] **Step 1: Add end-to-end HTTP test**

Add to `ServiceRegistryIntegrationTest`. Import `@Autowired TestRestTemplate` — add `webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT` to the `@SpringBootTest` annotation:

```java
// Change annotation to:
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {...})

@Autowired
org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

@Test
void fullRegistrationRoundTrip() {
    var req = new RegistrationRequest(
            "acme", "inventory-service", "inventory", "GRADLE",
            "service", null, null, "supply-chain"
    );

    var created = restTemplate.postForEntity("/registry/services", req, ServiceEntry.class);

    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(created.getHeaders().getLocation()).isNotNull();

    String serviceId = created.getBody().serviceId();
    var fetched = restTemplate.getForEntity(
            "/registry/services/" + serviceId, ServiceEntry.class);

    assertThat(fetched.getStatusCode().value()).isEqualTo(200);
    assertThat(fetched.getBody().orgId()).isEqualTo("acme");
    assertThat(fetched.getBody().enabled()).isTrue();
}
```

- [ ] **Step 2: Run full integration test**

```bash
mvn test -Dtest=ServiceRegistryIntegrationTest -q
```

Expected: All tests PASS.

- [ ] **Step 3: Run all registry tests together**

```bash
mvn test -Dtest="ServiceRegistryControllerTest,ServiceRegistryIntegrationTest" -q
```

Expected: All PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/testseer/backend/registry/ServiceRegistryIntegrationTest.java
git commit -m "test: add end-to-end HTTP integration test for service registry"
```

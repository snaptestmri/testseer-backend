# P3: GitHub Webhook Ingestion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept GitHub `pull_request` and `push` webhook events, validate HMAC-SHA256 signatures, look up registered services by changed file paths, decompose events into per-service `IngestionJob` records, and publish each job to the correct Kafka topic (`testseer.jobs.pr` or `testseer.jobs.batch`).

**Architecture:** `WebhookController` receives HTTP POST from GitHub and returns `202 Accepted` immediately. `GitHubSignatureValidator` verifies the HMAC. `JobDecomposer` maps changed file paths to services using registry `source_roots`. `KafkaJobPublisher` sends to the appropriate topic. Unregistered repos are silently dropped (log only).

**Tech Stack:** Spring Boot 3.3, Spring Kafka 3.x, Testcontainers Kafka (integration tests), MockMvc + Mockito (unit tests)

**Prerequisite:** P1 (schema), P2 (service registry) complete.

---

## File Structure

```
src/
├── main/java/io/testseer/backend/
│   └── webhook/
│       ├── IngestionJob.java               (Kafka message record)
│       ├── KafkaTopicsConfig.java          (topic bean definitions)
│       ├── GitHubSignatureValidator.java   (HMAC-SHA256 verification)
│       ├── JobDecomposer.java              (changed files → per-service jobs)
│       ├── KafkaJobPublisher.java          (sends to Kafka)
│       └── WebhookController.java          (POST /webhook/github)
└── test/java/io/testseer/backend/
    └── webhook/
        ├── GitHubSignatureValidatorTest.java
        ├── JobDecomposerTest.java
        ├── WebhookControllerTest.java
        └── WebhookKafkaIntegrationTest.java
```

---

### Task 1: `IngestionJob` record

**Files:**
- Create: `src/main/java/io/testseer/backend/webhook/IngestionJob.java`

- [ ] **Step 1: Create `IngestionJob.java`**

```java
package io.testseer.backend.webhook;

import java.time.Instant;
import java.util.List;

public record IngestionJob(
        String jobId,
        String jobType,      // "PR" | "PUSH" | "NIGHTLY"
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        List<String> changedFiles,
        Integer prNumber,    // null for PUSH/NIGHTLY
        Instant enqueuedAt,
        int attempt
) {}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/testseer/backend/webhook/IngestionJob.java
git commit -m "feat: add IngestionJob record for Kafka job envelope"
```

---

### Task 2: Kafka topic configuration

**Files:**
- Create: `src/main/java/io/testseer/backend/webhook/KafkaTopicsConfig.java`

- [ ] **Step 1: Create `KafkaTopicsConfig.java`**

```java
package io.testseer.backend.webhook;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    public static final String TOPIC_PR    = "testseer.jobs.pr";
    public static final String TOPIC_BATCH = "testseer.jobs.batch";
    public static final String TOPIC_DLQ   = "testseer.jobs.dlq";

    @Bean
    public NewTopic prTopic() {
        return TopicBuilder.name(TOPIC_PR)
                .partitions(12)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic batchTopic() {
        return TopicBuilder.name(TOPIC_BATCH)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(TOPIC_DLQ)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/testseer/backend/webhook/KafkaTopicsConfig.java
git commit -m "feat: define Kafka topics for PR, batch, and DLQ job queues"
```

---

### Task 3: `GitHubSignatureValidator`

**Files:**
- Create: `src/main/java/io/testseer/backend/webhook/GitHubSignatureValidator.java`
- Create: `src/test/java/io/testseer/backend/webhook/GitHubSignatureValidatorTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.testseer.backend.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubSignatureValidatorTest {

    private final GitHubSignatureValidator validator =
            new GitHubSignatureValidator("test-secret");

    @Test
    void validSignature_returnsTrue() {
        String payload = """{"action":"opened"}""";
        String sig = validator.computeSignature(payload);
        assertThat(validator.isValid(payload, sig)).isTrue();
    }

    @Test
    void wrongSecret_returnsFalse() {
        String payload = """{"action":"opened"}""";
        GitHubSignatureValidator other = new GitHubSignatureValidator("wrong-secret");
        String sig = other.computeSignature(payload);
        assertThat(validator.isValid(payload, sig)).isFalse();
    }

    @Test
    void nullSignature_returnsFalse() {
        assertThat(validator.isValid("""{"action":"opened"}""", null)).isFalse();
    }

    @Test
    void missingPrefix_returnsFalse() {
        String payload = """{"action":"opened"}""";
        String sig = validator.computeSignature(payload).replace("sha256=", "");
        assertThat(validator.isValid(payload, sig)).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=GitHubSignatureValidatorTest -q
```

Expected: All 4 FAIL — `GitHubSignatureValidator` does not exist.

- [ ] **Step 3: Create `GitHubSignatureValidator.java`**

```java
package io.testseer.backend.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class GitHubSignatureValidator {

    private final byte[] secretBytes;

    public GitHubSignatureValidator(
            @Value("${testseer.github.webhook-secret:changeme}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    // Package-visible constructor for unit tests
    GitHubSignatureValidator(String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String payload, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        String expected = computeSignature(payload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    String computeSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append("%02x".formatted(b));
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=GitHubSignatureValidatorTest -q
```

Expected: All 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/webhook/GitHubSignatureValidator.java \
        src/test/java/io/testseer/backend/webhook/GitHubSignatureValidatorTest.java
git commit -m "feat: add GitHubSignatureValidator with HMAC-SHA256 verification"
```

---

### Task 4: `JobDecomposer` — changed files to per-service jobs

**Files:**
- Create: `src/main/java/io/testseer/backend/webhook/JobDecomposer.java`
- Create: `src/test/java/io/testseer/backend/webhook/JobDecomposerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.testseer.backend.webhook;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobDecomposerTest {

    @Mock
    ServiceRegistryService registryService;

    @InjectMocks
    JobDecomposer decomposer;

    private static ServiceEntry service(String id, String sourceRoot) {
        return new ServiceEntry(
                id, "acme", "monorepo", "svc-" + id,
                "service", "MAVEN",
                List.of(sourceRoot), List.of("src/test/java"),
                null, true, Instant.now(), Instant.now()
        );
    }

    @Test
    void decompose_mapsChangedFilesToCorrectService() {
        when(registryService.listAll()).thenReturn(List.of(
                service("orders", "services/orders/src/main/java"),
                service("inventory", "services/inventory/src/main/java")
        ));

        List<IngestionJob> jobs = decomposer.decompose(
                "acme", "monorepo", "abc123", "PR", 42,
                List.of(
                        "services/orders/src/main/java/OrderController.java",
                        "services/inventory/src/main/java/InventoryService.java"
                )
        );

        assertThat(jobs).hasSize(2);
        assertThat(jobs).extracting(IngestionJob::serviceId)
                .containsExactlyInAnyOrder("orders", "inventory");
    }

    @Test
    void decompose_ignoresFilesOutsideRegisteredRoots() {
        when(registryService.listAll()).thenReturn(List.of(
                service("orders", "services/orders/src/main/java")
        ));

        List<IngestionJob> jobs = decomposer.decompose(
                "acme", "monorepo", "abc123", "PR", 42,
                List.of("README.md", "infra/terraform/main.tf")
        );

        assertThat(jobs).isEmpty();
    }

    @Test
    void decompose_deduplicate_multipleFilesInSameService() {
        when(registryService.listAll()).thenReturn(List.of(
                service("orders", "services/orders/src/main/java")
        ));

        List<IngestionJob> jobs = decomposer.decompose(
                "acme", "monorepo", "abc123", "PR", 42,
                List.of(
                        "services/orders/src/main/java/A.java",
                        "services/orders/src/main/java/B.java"
                )
        );

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).changedFiles()).hasSize(2);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=JobDecomposerTest -q
```

Expected: All 3 FAIL.

- [ ] **Step 3: Create `JobDecomposer.java`**

```java
package io.testseer.backend.webhook;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JobDecomposer {

    private final ServiceRegistryService registryService;

    public JobDecomposer(ServiceRegistryService registryService) {
        this.registryService = registryService;
    }

    public List<IngestionJob> decompose(
            String orgId, String repo, String commitSha,
            String jobType, Integer prNumber,
            List<String> changedFiles) {

        List<ServiceEntry> registered = registryService.listAll().stream()
                .filter(s -> s.orgId().equals(orgId) && s.repo().equals(repo) && s.enabled())
                .toList();

        Map<String, List<String>> serviceToFiles = new LinkedHashMap<>();

        for (String file : changedFiles) {
            for (ServiceEntry svc : registered) {
                boolean matches = svc.sourceRoots().stream()
                        .anyMatch(root -> file.startsWith(root + "/") || file.startsWith(root));
                if (matches) {
                    serviceToFiles.computeIfAbsent(svc.serviceId(), k -> new ArrayList<>()).add(file);
                    break;
                }
            }
        }

        return serviceToFiles.entrySet().stream()
                .map(entry -> new IngestionJob(
                        UUID.randomUUID().toString(),
                        jobType,
                        orgId,
                        repo,
                        entry.getKey(),
                        commitSha,
                        entry.getValue(),
                        prNumber,
                        Instant.now(),
                        1
                ))
                .toList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=JobDecomposerTest -q
```

Expected: All 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/testseer/backend/webhook/JobDecomposer.java \
        src/test/java/io/testseer/backend/webhook/JobDecomposerTest.java
git commit -m "feat: add JobDecomposer to map changed files to per-service ingestion jobs"
```

---

### Task 5: `KafkaJobPublisher`

**Files:**
- Create: `src/main/java/io/testseer/backend/webhook/KafkaJobPublisher.java`

- [ ] **Step 1: Create `KafkaJobPublisher.java`**

```java
package io.testseer.backend.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobPublisher.class);

    private final KafkaTemplate<String, IngestionJob> kafka;

    public KafkaJobPublisher(KafkaTemplate<String, IngestionJob> kafka) {
        this.kafka = kafka;
    }

    public void publishPrJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_PR, job);
    }

    public void publishBatchJob(IngestionJob job) {
        publish(KafkaTopicsConfig.TOPIC_BATCH, job);
    }

    private void publish(String topic, IngestionJob job) {
        kafka.send(topic, job.serviceId(), job)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish job {} to {}: {}", job.jobId(), topic, ex.getMessage());
                    } else {
                        log.debug("Published job {} to {} offset {}",
                                job.jobId(), topic, result.getRecordMetadata().offset());
                    }
                });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/testseer/backend/webhook/KafkaJobPublisher.java
git commit -m "feat: add KafkaJobPublisher to send IngestionJob to PR and batch topics"
```

---

### Task 6: `WebhookController` and integration test

**Files:**
- Create: `src/main/java/io/testseer/backend/webhook/WebhookController.java`
- Create: `src/test/java/io/testseer/backend/webhook/WebhookControllerTest.java`
- Create: `src/test/java/io/testseer/backend/webhook/WebhookKafkaIntegrationTest.java`

- [ ] **Step 1: Write failing MockMvc tests**

```java
package io.testseer.backend.webhook;

import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean GitHubSignatureValidator validator;
    @MockitoBean JobDecomposer decomposer;
    @MockitoBean KafkaJobPublisher publisher;

    private static final String PULL_REQUEST_PAYLOAD = """
            {
              "action": "synchronize",
              "number": 42,
              "pull_request": {
                "head": { "sha": "abc123" }
              },
              "repository": {
                "full_name": "acme/order-service",
                "owner": { "login": "acme" },
                "name": "order-service"
              },
              "installation": {}
            }
            """;

    @Test
    void pullRequest_withValidSignature_returns202() throws Exception {
        when(validator.isValid(any(), any())).thenReturn(true);
        when(decomposer.decompose(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=fakehash")
                        .contentType("application/json")
                        .content(PULL_REQUEST_PAYLOAD))
                .andExpect(status().isAccepted());
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        when(validator.isValid(any(), any())).thenReturn(false);

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=badsig")
                        .contentType("application/json")
                        .content(PULL_REQUEST_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownEvent_returns200_silently() throws Exception {
        when(validator.isValid(any(), any())).thenReturn(true);

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "star")
                        .header("X-Hub-Signature-256", "sha256=fakehash")
                        .contentType("application/json")
                        .content("""{"action":"created"}"""))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=WebhookControllerTest -q
```

Expected: All 3 FAIL.

- [ ] **Step 3: Create `WebhookController.java`**

```java
package io.testseer.backend.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/webhook/github")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final GitHubSignatureValidator signatureValidator;
    private final JobDecomposer decomposer;
    private final KafkaJobPublisher publisher;
    private final ObjectMapper mapper;

    public WebhookController(GitHubSignatureValidator signatureValidator,
                              JobDecomposer decomposer,
                              KafkaJobPublisher publisher,
                              ObjectMapper mapper) {
        this.signatureValidator = signatureValidator;
        this.decomposer = decomposer;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        if (!signatureValidator.isValid(payload, signature)) {
            log.warn("Rejected webhook with invalid signature for event={}", event);
            return ResponseEntity.status(401).build();
        }

        return switch (event) {
            case "pull_request" -> handlePullRequest(payload);
            case "push"         -> handlePush(payload);
            default -> {
                log.debug("Ignoring unhandled webhook event: {}", event);
                yield ResponseEntity.ok().build();
            }
        };
    }

    private ResponseEntity<Void> handlePullRequest(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            String action = node.path("action").asText();
            if (!List.of("opened", "synchronize", "reopened").contains(action)) {
                return ResponseEntity.ok().build();
            }

            String orgId     = node.path("repository").path("owner").path("login").asText();
            String repo      = node.path("repository").path("name").asText();
            String commitSha = node.path("pull_request").path("head").path("sha").asText();
            int    prNumber  = node.path("number").asInt();

            List<String> changedFiles = extractChangedFiles(node);
            List<IngestionJob> jobs = decomposer.decompose(
                    orgId, repo, commitSha, "PR", prNumber, changedFiles);

            if (jobs.isEmpty()) {
                log.info("No registered services matched changed files for {}/{} PR#{}", orgId, repo, prNumber);
            }
            jobs.forEach(publisher::publishPrJob);

        } catch (Exception ex) {
            log.error("Failed to process pull_request webhook: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.accepted().build();
    }

    private ResponseEntity<Void> handlePush(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            String ref  = node.path("ref").asText();
            if (!ref.startsWith("refs/heads/")) return ResponseEntity.ok().build();

            String orgId     = node.path("repository").path("owner").path("login").asText();
            String repo      = node.path("repository").path("name").asText();
            String commitSha = node.path("after").asText();

            List<String> changedFiles = extractChangedFiles(node);
            List<IngestionJob> jobs = decomposer.decompose(
                    orgId, repo, commitSha, "PUSH", null, changedFiles);

            jobs.forEach(publisher::publishBatchJob);

        } catch (Exception ex) {
            log.error("Failed to process push webhook: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.accepted().build();
    }

    private List<String> extractChangedFiles(JsonNode node) {
        var commits = node.path("commits");
        if (commits.isMissingNode()) {
            // For pull_request events, files come from a subsequent API call
            // (handled by the analysis worker — webhook just passes the SHA)
            return List.of();
        }
        var files = new java.util.ArrayList<String>();
        for (JsonNode commit : commits) {
            for (JsonNode f : commit.path("added"))    files.add(f.asText());
            for (JsonNode f : commit.path("modified")) files.add(f.asText());
        }
        return files;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=WebhookControllerTest -q
```

Expected: All 3 PASS.

- [ ] **Step 5: Write Kafka integration test**

```java
package io.testseer.backend.webhook;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(
    topics = {KafkaTopicsConfig.TOPIC_PR, KafkaTopicsConfig.TOPIC_BATCH},
    partitions = 1
)
class WebhookKafkaIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired MockMvc mockMvc;
    @Autowired ServiceRegistryService registryService;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired GitHubSignatureValidator signatureValidator;

    @Test
    void pullRequestWebhook_publishesJobToprTopic() throws Exception {
        registryService.register(new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                "service", List.of("src/main/java"), List.of("src/test/java"), null
        ));

        String payload = """
                {
                  "action": "synchronize",
                  "number": 99,
                  "pull_request": { "head": { "sha": "deadbeef" } },
                  "repository": {
                    "owner": { "login": "acme" },
                    "name": "order-service"
                  },
                  "commits": [{
                    "modified": ["src/main/java/OrderController.java"]
                  }]
                }
                """;
        String sig = signatureValidator.computeSignature(payload);

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", sig)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted());

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-consumer-" + System.currentTimeMillis(), "false", broker);
        consumerProps.put("value.deserializer",
                "org.springframework.kafka.support.serializer.JsonDeserializer");
        consumerProps.put("spring.json.value.default.type",
                "io.testseer.backend.webhook.IngestionJob");

        try (Consumer<String, IngestionJob> consumer =
                     new DefaultKafkaConsumerFactory<String, IngestionJob>(consumerProps)
                             .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, KafkaTopicsConfig.TOPIC_PR);
            ConsumerRecord<String, IngestionJob> record =
                    KafkaTestUtils.getSingleRecord(consumer, KafkaTopicsConfig.TOPIC_PR, 5000);

            assertThat(record.value().jobType()).isEqualTo("PR");
            assertThat(record.value().orgId()).isEqualTo("acme");
            assertThat(record.value().commitSha()).isEqualTo("deadbeef");
        }
    }
}
```

- [ ] **Step 6: Run integration test**

```bash
mvn test -Dtest=WebhookKafkaIntegrationTest -q
```

Expected: PASS — job published to `testseer.jobs.pr`.

- [ ] **Step 7: Run all webhook tests**

```bash
mvn test -Dtest="GitHubSignatureValidatorTest,JobDecomposerTest,WebhookControllerTest,WebhookKafkaIntegrationTest" -q
```

Expected: All PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/testseer/backend/webhook/ \
        src/test/java/io/testseer/backend/webhook/
git commit -m "feat: add WebhookController with signature validation, decomposition, and Kafka publishing"
```

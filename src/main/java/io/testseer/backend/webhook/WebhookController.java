package io.testseer.backend.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import io.testseer.backend.observability.TestSeerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Webhook", description = "GitHub webhook receiver for push and pull_request events")
@RestController
@RequestMapping("/webhook/github")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final GitHubSignatureValidator signatureValidator;
    private final JobDecomposer decomposer;
    private final KafkaJobPublisher publisher;
    private final ObjectMapper mapper;
    private final TestSeerMetrics metrics;
    private final GitHubSourceFetcher sourceFetcher;
    private final WebhookCatalogRegistrar catalogRegistrar;

    public WebhookController(GitHubSignatureValidator signatureValidator,
                              JobDecomposer decomposer,
                              KafkaJobPublisher publisher,
                              ObjectMapper mapper,
                              TestSeerMetrics metrics,
                              GitHubSourceFetcher sourceFetcher,
                              WebhookCatalogRegistrar catalogRegistrar) {
        this.signatureValidator = signatureValidator;
        this.decomposer = decomposer;
        this.publisher = publisher;
        this.mapper = mapper;
        this.metrics = metrics;
        this.sourceFetcher = sourceFetcher;
        this.catalogRegistrar = catalogRegistrar;
    }

    @Operation(summary = "Receive GitHub webhook",
               description = """
                   Accepts GitHub `push` and `pull_request` webhook events. \
                   Validates the HMAC-SHA256 signature, extracts changed files (including OpenAPI JSON via \
                   GitHub PR files API when needed), and queues analysis jobs to Kafka. Returns 202 Accepted on success.""")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Webhook accepted and queued"),
        @ApiResponse(responseCode = "200", description = "Webhook received but ignored (unsupported event or action)"),
        @ApiResponse(responseCode = "401", description = "Invalid HMAC signature")
    })
    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @Parameter(description = "GitHub event type (push, pull_request)", required = true)
            @RequestHeader("X-GitHub-Event") String event,
            @Parameter(description = "HMAC-SHA256 signature for payload verification")
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        if (!signatureValidator.isValid(payload, signature)) {
            log.warn("Rejected webhook with invalid signature for event={}", event);
            metrics.recordWebhookRejected("invalid_signature");
            return ResponseEntity.status(401).build();
        }

        metrics.recordWebhookReceived(event);

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
            if (changedFiles.isEmpty()) {
                changedFiles = sourceFetcher.fetchPullRequestChangedFiles(orgId, repo, prNumber);
            }

            catalogRegistrar.ensureCatalogRegistered(orgId, repo);
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

            catalogRegistrar.ensureCatalogRegistered(orgId, repo);
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
            return List.of();
        }
        var files = new ArrayList<String>();
        for (JsonNode commit : commits) {
            for (JsonNode f : commit.path("added"))    files.add(f.asText());
            for (JsonNode f : commit.path("modified")) files.add(f.asText());
        }
        return files;
    }
}

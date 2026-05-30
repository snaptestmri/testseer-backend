package io.testseer.backend.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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

package io.testseer.backend.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class PrCommentPublisher {

    private static final Logger log = LoggerFactory.getLogger(PrCommentPublisher.class);

    private final RestClient restClient;
    private final boolean commentEnabled;
    private final String githubToken;

    @Autowired
    public PrCommentPublisher(
            @Value("${testseer.github.token:}") String githubToken,
            @Value("${testseer.github.comment-enabled:false}") boolean commentEnabled) {
        this.githubToken = githubToken;
        this.commentEnabled = commentEnabled;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (!githubToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + githubToken);
        }
        this.restClient = builder.build();
    }

    PrCommentPublisher(RestClient restClient, String githubToken, boolean commentEnabled) {
        this.restClient = restClient;
        this.githubToken = githubToken;
        this.commentEnabled = commentEnabled;
    }

    public boolean isEnabled() {
        return commentEnabled && githubToken != null && !githubToken.isBlank();
    }

    public void publishOrUpdate(String orgId, String repo, int prNumber, String body) {
        if (!isEnabled()) {
            log.debug("PR comment publishing disabled (comment-enabled={}, token present={})",
                    commentEnabled, githubToken != null && !githubToken.isBlank());
            return;
        }

        Long existingId = findExistingCommentId(orgId, repo, prNumber);
        if (existingId != null) {
            updateComment(orgId, repo, existingId, body);
            log.info("Updated TestSeer PR comment on {}/{}#{}", orgId, repo, prNumber);
        } else {
            createComment(orgId, repo, prNumber, body);
            log.info("Posted TestSeer PR comment on {}/{}#{}", orgId, repo, prNumber);
        }
    }

    private Long findExistingCommentId(String orgId, String repo, int prNumber) {
        List<Map<String, Object>> comments = restClient.get()
                .uri("/repos/{owner}/{repo}/issues/{issue}/comments", orgId, repo, prNumber)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {});

        if (comments == null) {
            return null;
        }
        for (Map<String, Object> comment : comments) {
            Object body = comment.get("body");
            if (body instanceof String text && text.contains(PrCommentFormatter.MARKER)) {
                Object id = comment.get("id");
                if (id instanceof Number number) {
                    return number.longValue();
                }
            }
        }
        return null;
    }

    private void createComment(String orgId, String repo, int prNumber, String body) {
        restClient.post()
                .uri("/repos/{owner}/{repo}/issues/{issue}/comments", orgId, repo, prNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    private void updateComment(String orgId, String repo, long commentId, String body) {
        restClient.patch()
                .uri("/repos/{owner}/{repo}/issues/comments/{commentId}", orgId, repo, commentId)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }
}

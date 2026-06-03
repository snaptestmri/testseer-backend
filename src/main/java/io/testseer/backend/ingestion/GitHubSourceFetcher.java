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

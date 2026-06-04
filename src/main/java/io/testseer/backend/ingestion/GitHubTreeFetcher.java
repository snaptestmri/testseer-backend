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

    @SuppressWarnings("unchecked")
    public List<String> fetchJavaPaths(String orgId, String repo, String commitSha) {
        Map<String, Object> response = restClient.get()
                .uri("/repos/{org}/{repo}/git/trees/{sha}?recursive=1",
                        orgId, repo, commitSha)
                .retrieve()
                .body(Map.class);

        if (response == null) return List.of();

        if (Boolean.TRUE.equals(response.get("truncated"))) {
            log.warn("Git tree truncated for {}/{} — large repo, results may be incomplete",
                    orgId, repo);
        }

        List<Map<String, String>> tree =
                (List<Map<String, String>>) response.get("tree");
        if (tree == null) return List.of();

        return tree.stream()
                .filter(e -> "blob".equals(e.get("type")))
                .map(e -> e.get("path"))
                .filter(p -> p != null && p.endsWith(".java"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public String resolveHeadSha(String orgId, String repo) {
        Map<String, Object> response = restClient.get()
                .uri("/repos/{org}/{repo}/commits/HEAD", orgId, repo)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException(
                    "GitHub returned null for HEAD commit of " + orgId + "/" + repo);
        }
        return (String) response.get("sha");
    }
}

package io.testseer.backend.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class GitHubTreeFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubTreeFetcher.class);

    private final RestClient restClient;

    @Autowired
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
        return fetchBlobPaths(orgId, repo, commitSha).stream()
                .filter(p -> p.endsWith(".java"))
                .toList();
    }

    /** JSON paths under configured catalog source roots (BL-046 OpenAPI index). */
    public List<String> fetchJsonPaths(
            String orgId, String repo, String commitSha, List<String> sourceRoots) {
        return fetchBlobPaths(orgId, repo, commitSha).stream()
                .filter(p -> p.endsWith(".json"))
                .filter(p -> underAnyRoot(p, sourceRoots))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchBlobPaths(String orgId, String repo, String commitSha) {
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
                .filter(p -> p != null)
                .toList();
    }

    static boolean underAnyRoot(String path, List<String> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            return true;
        }
        for (String root : sourceRoots) {
            if (root == null || root.isBlank()) continue;
            if (path.equals(root) || path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
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

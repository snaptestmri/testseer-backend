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
                log.debug("Skipping {}/{} — no Java build file found", orgId, name);
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
     * Parses GitHub's Link header to extract the "next" page path.
     * Format: <https://api.github.com/orgs/acme/repos?page=2>; rel="next"
     * Returns the path (without base URL) or null if no next page.
     */
    static String extractNextPageUri(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return null;
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<') + 1;
                int end   = part.indexOf('>');
                if (start > 0 && end > start) {
                    String full = part.substring(start, end).trim();
                    return full.replaceFirst("https://api\\.github\\.com", "");
                }
            }
        }
        return null;
    }

    public record DetectedRepo(String name, String buildTool, String defaultBranch) {}
}

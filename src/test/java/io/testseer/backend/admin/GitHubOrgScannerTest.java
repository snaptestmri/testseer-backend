package io.testseer.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GitHubOrgScannerTest {

    @Test
    void extractNextPageUri_parsesLinkHeader() {
        String link = "<https://api.github.com/orgs/acme/repos?page=2>; rel=\"next\", " +
                      "<https://api.github.com/orgs/acme/repos?page=5>; rel=\"last\"";
        String next = GitHubOrgScanner.extractNextPageUri(link);
        assertThat(next).isEqualTo("/orgs/acme/repos?page=2");
    }

    @Test
    void extractNextPageUri_returnsNull_whenNoNextLink() {
        String link = "<https://api.github.com/orgs/acme/repos?page=5>; rel=\"last\"";
        assertThat(GitHubOrgScanner.extractNextPageUri(link)).isNull();
    }

    @Test
    void extractNextPageUri_returnsNull_whenHeaderNull() {
        assertThat(GitHubOrgScanner.extractNextPageUri(null)).isNull();
    }

    @Test
    void scanJavaRepos_skipsArchivedAndForks() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        HttpHeaders noLink = new HttpHeaders();

        when(restClient.get().uri(anyString(), (Object) any()).retrieve()
                .toEntity(List.class))
                .thenReturn(ResponseEntity.ok().headers(noLink).body(List.of(
                        Map.of("name", "archived-svc", "default_branch", "main",
                               "archived", true,  "fork", false),
                        Map.of("name", "forked-svc",   "default_branch", "main",
                               "archived", false, "fork", true)
                )));

        GitHubOrgScanner scanner = new GitHubOrgScanner(restClient);
        List<GitHubOrgScanner.DetectedRepo> repos = scanner.scanJavaRepos("acme");

        assertThat(repos).isEmpty();
    }

    @Test
    void scanJavaRepos_detectsMavenRepo() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        HttpHeaders noLink = new HttpHeaders();

        // List call
        when(restClient.get().uri(anyString(), (Object) any()).retrieve()
                .toEntity(List.class))
                .thenReturn(ResponseEntity.ok().headers(noLink).body(List.of(
                        Map.of("name", "orders", "default_branch", "main",
                               "archived", false, "fork", false)
                )));

        // pom.xml exists, build.gradle does not
        when(restClient.get().uri(anyString(), any(), any(), any()).retrieve()
                .body(Map.class))
                .thenReturn(Map.of("name", "pom.xml"))  // pom.xml call
                .thenReturn(null);                      // build.gradle call (not reached)

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
        HttpHeaders noLink = new HttpHeaders();

        when(restClient.get().uri(anyString(), (Object) any()).retrieve()
                .toEntity(List.class))
                .thenReturn(ResponseEntity.ok().headers(noLink).body(List.of(
                        Map.of("name", "frontend", "default_branch", "main",
                               "archived", false, "fork", false)
                )));

        // Both pom.xml and build.gradle 404
        when(restClient.get().uri(anyString(), any(), any(), any()).retrieve()
                .body(Map.class))
                .thenThrow(HttpClientErrorException.NotFound.class);

        GitHubOrgScanner scanner = new GitHubOrgScanner(restClient);
        List<GitHubOrgScanner.DetectedRepo> repos = scanner.scanJavaRepos("acme");

        assertThat(repos).isEmpty();
    }
}

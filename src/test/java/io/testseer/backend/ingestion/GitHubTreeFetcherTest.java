package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GitHubTreeFetcherTest {

    @Test
    void fetchJavaPaths_filtersToJavaFiles() {
        var responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        var restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
                "truncated", false,
                "tree", List.of(
                        Map.of("path", "src/main/java/Foo.java",  "type", "blob"),
                        Map.of("path", "src/main/java/Bar.java",  "type", "blob"),
                        Map.of("path", "README.md",               "type", "blob"),
                        Map.of("path", "src/main/java/pkg",       "type", "tree")
                )
        ));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        List<String> paths = fetcher.fetchJavaPaths("acme", "orders", "abc123");

        assertThat(paths).containsExactlyInAnyOrder(
                "src/main/java/Foo.java",
                "src/main/java/Bar.java"
        );
    }

    @Test
    void resolveHeadSha_returnsCommitSha() {
        var responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        var restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("sha", "deadbeef"));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        String sha = fetcher.resolveHeadSha("acme", "orders");

        assertThat(sha).isEqualTo("deadbeef");
    }

    @Test
    void fetchJavaPaths_returnsPartialResults_whenTruncated() {
        var responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        var restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
                "truncated", true,
                "tree", List.of(Map.of("path", "src/Foo.java", "type", "blob"))
        ));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        List<String> paths = fetcher.fetchJavaPaths("acme", "orders", "abc123");

        // Truncation is a warning, not a failure — still returns what was found
        assertThat(paths).hasSize(1);
    }

    @Test
    void fetchJsonPaths_filtersBySourceRoots() {
        var responseSpec = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var uriSpec = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        var restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
                "truncated", false,
                "tree", List.of(
                        Map.of("path", "reference/Offers/Foo.json", "type", "blob"),
                        Map.of("path", "Common/Models/Bar.json", "type", "blob"),
                        Map.of("path", "README.json", "type", "blob"),
                        Map.of("path", "src/main/java/Foo.java", "type", "blob")
                )
        ));

        GitHubTreeFetcher fetcher = new GitHubTreeFetcher(restClient);
        List<String> paths = fetcher.fetchJsonPaths(
                "quotient", "riq-platform-apis-optimus", "abc123",
                List.of("reference", "Common/Models"));

        assertThat(paths).containsExactlyInAnyOrder(
                "reference/Offers/Foo.json",
                "Common/Models/Bar.json"
        );
    }
}

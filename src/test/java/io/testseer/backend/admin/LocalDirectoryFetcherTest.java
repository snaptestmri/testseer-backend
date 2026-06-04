package io.testseer.backend.admin;

import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDirectoryFetcherTest {

    private final LocalDirectoryFetcher fetcher = new LocalDirectoryFetcher();

    @Test
    void fetchJavaFiles_returnsAllJavaFilesRecursively(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Foo.java"), "class Foo {}");
        Files.writeString(pkg.resolve("Bar.java"), "class Bar {}");
        Files.writeString(pkg.resolve("README.md"), "# readme");

        List<GitHubSourceFetcher.FetchedFile> files =
                fetcher.fetchJavaFiles(tmp.toString());

        assertThat(files).hasSize(2);
        assertThat(files).allMatch(f -> f.path().endsWith(".java"));
        assertThat(files.stream().map(GitHubSourceFetcher.FetchedFile::content))
                .anyMatch(c -> c.contains("class Foo"));
    }

    @Test
    void fetchJavaFiles_throwsIllegalArgument_whenPathNotDirectory(@TempDir Path tmp) {
        assertThatThrownBy(() -> fetcher.fetchJavaFiles(tmp.resolve("nonexistent").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void detectBuildTool_returnsMaven_whenPomXmlPresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        assertThat(fetcher.detectBuildTool(tmp.toString())).isEqualTo("MAVEN");
    }

    @Test
    void detectBuildTool_returnsGradle_whenBuildGradlePresent(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("build.gradle"), "plugins {}");
        assertThat(fetcher.detectBuildTool(tmp.toString())).isEqualTo("GRADLE");
    }

    @Test
    void detectBuildTool_returnsNull_whenNeitherPresent(@TempDir Path tmp) {
        assertThat(fetcher.detectBuildTool(tmp.toString())).isNull();
    }

    @Test
    void resolveGitSha_returnsFallback_whenNotGitRepo(@TempDir Path tmp) {
        String sha = fetcher.resolveGitSha(tmp.toString());
        assertThat(sha).startsWith("local-");
    }
}

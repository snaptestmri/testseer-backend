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
    void fetchJavaFiles_readsLegacyWindows1252Javadoc(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.write(pkg.resolve("Legacy.java"), new byte[] {
                '/', '*', '*', ' ', 'X', (byte) 0x92, '0', '0', (byte) 0x92, ' ', '*', '/', '\n',
                'c', 'l', 'a', 's', 's', ' ', 'L', 'e', 'g', 'a', 'c', 'y', ' ', '{', '}'
        });

        List<GitHubSourceFetcher.FetchedFile> files =
                fetcher.fetchJavaFiles(tmp.toString());

        assertThat(files).singleElement()
                .extracting(GitHubSourceFetcher.FetchedFile::content)
                .asString()
                .contains("class Legacy")
                .contains("X\u201900\u2019");
    }

    @Test
    void readTextFile_acceptsUtf8(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("utf8.java");
        Files.writeString(file, "class Utf8 { /* café */ }");

        assertThat(LocalDirectoryFetcher.readTextFile(file)).contains("café");
    }

    @Test
    void fetchJavaFilesFromRoots_preservesModulePrefixInPaths(@TempDir Path tmp) throws IOException {
        Path consumerRoot = Files.createDirectories(
                tmp.resolve("partner-adapter-consumer/src/main/java/com/example"));
        Files.writeString(consumerRoot.resolve("OfferConsumer.java"), "class OfferConsumer {}");

        Path libRoot = Files.createDirectories(
                tmp.resolve("partner-adapter-lib/src/main/java/com/example"));
        Files.writeString(libRoot.resolve("Helper.java"), "class Helper {}");

        List<GitHubSourceFetcher.FetchedFile> files = fetcher.fetchJavaFilesFromRoots(
                tmp.toString(),
                List.of(
                        "partner-adapter-consumer/src/main/java",
                        "partner-adapter-lib/src/main/java"));

        assertThat(files).hasSize(2);
        assertThat(files.stream().map(GitHubSourceFetcher.FetchedFile::path))
                .containsExactlyInAnyOrder(
                        "partner-adapter-consumer/src/main/java/com/example/OfferConsumer.java",
                        "partner-adapter-lib/src/main/java/com/example/Helper.java");
    }

    @Test
    void resolveGitSha_returnsFallback_whenNotGitRepo(@TempDir Path tmp) {
        String sha = fetcher.resolveGitSha(tmp.toString());
        assertThat(sha).startsWith("local-");
    }
}

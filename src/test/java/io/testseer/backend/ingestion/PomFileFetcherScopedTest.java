package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PomFileFetcherScopedTest {

    @TempDir Path tempDir;

    @Test
    void fetchScoped_includesRootAndModulePomsOnly() throws IOException {
        writePom(tempDir.resolve("pom.xml"), "root");
        Path moduleA = tempDir.resolve("module-a");
        Files.createDirectories(moduleA);
        writePom(moduleA.resolve("pom.xml"), "a");
        Path moduleB = tempDir.resolve("module-b");
        Files.createDirectories(moduleB);
        writePom(moduleB.resolve("pom.xml"), "b");

        PomFileFetcher fetcher = new PomFileFetcher();
        List<GitHubSourceFetcher.FetchedFile> scoped =
                fetcher.fetchScoped(tempDir.toString(), List.of("module-a"));

        assertThat(scoped).extracting(GitHubSourceFetcher.FetchedFile::path)
                .containsExactlyInAnyOrder("pom.xml", "module-a/pom.xml");
        assertThat(scoped).extracting(GitHubSourceFetcher.FetchedFile::content)
                .allMatch(c -> c.contains("root") || c.contains("a"));
    }

    private static void writePom(Path path, String marker) throws IOException {
        Files.writeString(path, "<project><artifactId>" + marker + "</artifactId></project>");
    }
}

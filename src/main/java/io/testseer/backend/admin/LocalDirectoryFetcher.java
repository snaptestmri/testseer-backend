package io.testseer.backend.admin;

import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class LocalDirectoryFetcher {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryFetcher.class);

    public List<GitHubSourceFetcher.FetchedFile> fetchJavaFiles(String path) {
        Path dir = Path.of(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            String content = Files.readString(p);
                            String relativePath = dir.relativize(p).toString();
                            return new GitHubSourceFetcher.FetchedFile(relativePath, content);
                        } catch (IOException ex) {
                            log.warn("Could not read {}: {}", p, ex.getMessage());
                            return null;
                        }
                    })
                    .filter(f -> f != null)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not walk directory: " + path, ex);
        }
    }

    public String detectBuildTool(String path) {
        Path dir = Path.of(path);
        if (Files.exists(dir.resolve("pom.xml")))      return "MAVEN";
        if (Files.exists(dir.resolve("build.gradle"))) return "GRADLE";
        return null;
    }

    public String resolveGitSha(String path) {
        try {
            Process process = new ProcessBuilder("git", "-C", path, "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception ex) {
            log.debug("Could not resolve git SHA for {}: {}", path, ex.getMessage());
        }
        return "local-" + Instant.now().toEpochMilli();
    }
}

package io.testseer.backend.admin;

import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class LocalDirectoryFetcher {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryFetcher.class);

    public List<GitHubSourceFetcher.FetchedFile> fetchJavaFiles(String path) {
        return fetchByExtension(path, ".java");
    }

    public List<GitHubSourceFetcher.FetchedFile> fetchJavaFilesFromRoots(String path, List<String> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()
                || sourceRoots.size() == 1 && "src/main/java".equals(sourceRoots.get(0))) {
            return fetchJavaFiles(path);
        }
        Path base = Path.of(path);
        List<GitHubSourceFetcher.FetchedFile> combined = new ArrayList<>();
        for (String root : sourceRoots) {
            Path sub = base.resolve(root);
            if (Files.isDirectory(sub)) {
                String pathPrefix = root.replace('\\', '/');
                combined.addAll(fetchByExtension(sub.toString(), pathPrefix, ".java"));
                if (root.contains("kotlin")) {
                    combined.addAll(fetchByExtension(sub.toString(), pathPrefix, ".kt"));
                }
            } else {
                log.warn("Configured source root missing: {}", sub);
            }
        }
        return combined;
    }

    public List<GitHubSourceFetcher.FetchedFile> fetchDdlFiles(String path) {
        List<GitHubSourceFetcher.FetchedFile> sql = fetchByExtension(path, ".sql");
        List<GitHubSourceFetcher.FetchedFile> cql = fetchByExtension(path, ".cql");
        List<GitHubSourceFetcher.FetchedFile> combined = new ArrayList<>(sql.size() + cql.size());
        combined.addAll(sql);
        combined.addAll(cql);
        return combined;
    }

    public List<GitHubSourceFetcher.FetchedFile> fetchJsonFilesFromRoots(String path, List<String> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            return fetchByExtension(path, ".json");
        }
        Path base = Path.of(path);
        List<GitHubSourceFetcher.FetchedFile> combined = new ArrayList<>();
        for (String root : sourceRoots) {
            Path sub = base.resolve(root);
            if (Files.isDirectory(sub)) {
                combined.addAll(fetchByExtension(sub.toString(), root.replace('\\', '/'), ".json"));
            } else {
                log.warn("Configured source root missing: {}", sub);
            }
        }
        return combined;
    }

    private List<GitHubSourceFetcher.FetchedFile> fetchByExtension(String path, String extension) {
        return fetchByExtension(path, "", extension);
    }

    /**
     * Walks {@code path} and returns files with repo-relative paths. When {@code pathPrefix} is set
     * (e.g. {@code partner-adapter-consumer/src/main/java}), it is prepended so
     * {@link io.testseer.backend.ingestion.messaging.EnvLaneResolver#resolveModuleName} aligns
     * Java sources with YAML config from the same Maven module.
     */
    private List<GitHubSourceFetcher.FetchedFile> fetchByExtension(
            String path, String pathPrefix, String extension) {
        Path dir = Path.of(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }
        String prefix = pathPrefix == null ? "" : pathPrefix.replace('\\', '/');
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        try (var stream = Files.walk(dir)) {
            final String pathPrefixFinal = prefix;
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(extension))
                    .map(p -> {
                        try {
                            String content = readTextFile(p);
                            String relativePath = dir.relativize(p).toString().replace('\\', '/');
                            if (!pathPrefixFinal.isEmpty()) {
                                relativePath = pathPrefixFinal + relativePath;
                            }
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

    /**
     * Reads text files that may be UTF-8 or legacy Windows-1252 (common in older Java Javadoc).
     * {@link Files#readString} rejects invalid UTF-8 sequences such as smart-quote bytes {@code 0x92}.
     */
    static String readTextFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, Charset.forName("Windows-1252"));
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }
}

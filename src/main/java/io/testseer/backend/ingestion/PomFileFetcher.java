package io.testseer.backend.ingestion;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class PomFileFetcher {

    public List<GitHubSourceFetcher.FetchedFile> fetchFromDirectory(String rootPath) {
        Path dir = Path.of(rootPath);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("pom.xml"))
                    .map(p -> readFile(dir, p))
                    .filter(f -> f != null)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * Fetches the repo root {@code pom.xml} plus all {@code pom.xml} files under each {@code pomRoot}
     * (workspace catalog module). Empty {@code pomRoots} delegates to {@link #fetchFromDirectory}.
     */
    public List<GitHubSourceFetcher.FetchedFile> fetchScoped(String rootPath, List<String> pomRoots) {
        if (pomRoots == null || pomRoots.isEmpty()) {
            return fetchFromDirectory(rootPath);
        }
        Path root = Path.of(rootPath);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Map<String, GitHubSourceFetcher.FetchedFile> deduped = new LinkedHashMap<>();
        Path rootPom = root.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            GitHubSourceFetcher.FetchedFile file = readFile(root, rootPom);
            if (file != null) {
                deduped.put(file.path(), file);
            }
        }
        for (String pomRoot : pomRoots) {
            if (pomRoot == null) {
                continue;
            }
            String normalized = pomRoot.replace('\\', '/').replaceAll("/+$", "");
            Path sub = normalized.isBlank() ? root : root.resolve(normalized);
            if (!Files.isDirectory(sub)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(sub)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase("pom.xml"))
                        .forEach(p -> {
                            GitHubSourceFetcher.FetchedFile file = readFile(root, p);
                            if (file != null) {
                                deduped.put(file.path(), file);
                            }
                        });
            } catch (IOException ex) {
                // skip unreadable module root
            }
        }
        return List.copyOf(deduped.values());
    }

    public List<GitHubSourceFetcher.FetchedFile> fetchFromGitHubPaths(
            GitHubSourceFetcher fetcher,
            String orgId,
            String repo,
            String commitSha,
            List<String> paths) {
        List<GitHubSourceFetcher.FetchedFile> result = new ArrayList<>();
        for (String path : paths) {
            if (!isPomPath(path)) {
                continue;
            }
            GitHubSourceFetcher.FetchedFile file = fetcher.fetchFile(orgId, repo, commitSha, path);
            if (file != null) {
                result.add(file);
            }
        }
        return result;
    }

    public List<String> filterPomPaths(List<String> paths) {
        return paths.stream().filter(this::isPomPath).toList();
    }

    public List<GitHubSourceFetcher.FetchedFile> fetchPomFiles(
            GitHubSourceFetcher fetcher,
            String orgId,
            String repo,
            String commitSha,
            List<String> changedFiles) {
        return fetchPomFiles(fetcher, orgId, repo, commitSha, changedFiles, false);
    }

    /** Fetches poms from changed paths; on baseline jobs also tries root {@code pom.xml} when no pom changed. */
    public List<GitHubSourceFetcher.FetchedFile> fetchPomFiles(
            GitHubSourceFetcher fetcher,
            String orgId,
            String repo,
            String commitSha,
            List<String> changedFiles,
            boolean baselineSnapshot) {
        List<GitHubSourceFetcher.FetchedFile> fromChanged =
                fetchFromGitHubPaths(fetcher, orgId, repo, commitSha, changedFiles);
        if (!fromChanged.isEmpty() || !baselineSnapshot) {
            return fromChanged;
        }
        GitHubSourceFetcher.FetchedFile root = fetcher.fetchFile(orgId, repo, commitSha, "pom.xml");
        return root != null ? List.of(root) : List.of();
    }

    private boolean isPomPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.endsWith("/pom.xml") || normalized.equals("pom.xml");
    }

    private GitHubSourceFetcher.FetchedFile readFile(Path repoRoot, Path file) {
        try {
            String relative = repoRoot.relativize(file).toString().replace('\\', '/');
            String content = Files.readString(file);
            return new GitHubSourceFetcher.FetchedFile(relative, content);
        } catch (IOException ex) {
            return null;
        }
    }
}

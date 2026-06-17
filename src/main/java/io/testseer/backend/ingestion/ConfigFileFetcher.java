package io.testseer.backend.ingestion;

import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class ConfigFileFetcher {

    public List<YamlPubSubExtractor.ConfigFile> fetchFromDirectory(String rootPath) {
        return fetchFromRoots(rootPath, null);
    }

    /** Scans optional sub-roots for YAML application config and proto files (catalog libraries). */
    public List<YamlPubSubExtractor.ConfigFile> fetchFromRoots(String rootPath, List<String> sourceRoots) {
        Path dir = Path.of(rootPath);
        if (!Files.isDirectory(dir)) return List.of();
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            return walkConfigFiles(dir, dir);
        }
        List<YamlPubSubExtractor.ConfigFile> combined = new java.util.ArrayList<>();
        for (String root : sourceRoots) {
            Path sub = dir.resolve(root);
            if (Files.isDirectory(sub)) {
                combined.addAll(walkConfigFiles(dir, sub));
            }
        }
        return combined;
    }

    private List<YamlPubSubExtractor.ConfigFile> walkConfigFiles(Path repoRoot, Path scanRoot) {
        try (Stream<Path> stream = Files.walk(scanRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isConfigFile)
                    .map(p -> readFile(repoRoot, p))
                    .filter(f -> f != null)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public List<YamlPubSubExtractor.ConfigFile> fetchFromGitHubPaths(
            GitHubSourceFetcher fetcher,
            String orgId,
            String repo,
            String commitSha,
            List<String> paths) {
        return paths.stream()
                .filter(this::isConfigPath)
                .map(path -> {
                    GitHubSourceFetcher.FetchedFile file =
                            fetcher.fetchFile(orgId, repo, commitSha, path);
                    return file != null
                            ? new YamlPubSubExtractor.ConfigFile(file.path(), file.content())
                            : null;
                })
                .filter(f -> f != null)
                .toList();
    }

    public List<String> filterConfigPaths(List<String> paths) {
        return paths.stream().filter(this::isConfigPath).toList();
    }

    private YamlPubSubExtractor.ConfigFile readFile(Path repoRoot, Path file) {
        try {
            String relative = repoRoot.relativize(file).toString();
            return new YamlPubSubExtractor.ConfigFile(relative, Files.readString(file));
        } catch (IOException ex) {
            return null;
        }
    }

    private boolean isConfigFile(Path path) {
        return isConfigPath(path.toString());
    }

    private boolean isConfigPath(String path) {
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (lower.endsWith(".proto")) return true;
        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) return false;
        if (lower.contains("kubernetes-manifests/") || lower.contains("/cronjob")) return true;
        return lower.contains("/src/main/resources/application")
                || lower.contains("application-pdn")
                || lower.contains("application-qa")
                || lower.contains("application-prod")
                || lower.contains("application-dev");
    }
}

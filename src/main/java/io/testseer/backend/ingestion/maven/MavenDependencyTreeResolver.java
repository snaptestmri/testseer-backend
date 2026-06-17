package io.testseer.backend.ingestion.maven;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs {@code mvn dependency:tree} and parses text output into resolved dependency rows. */
@Component
public class MavenDependencyTreeResolver {

    private static final Logger log = LoggerFactory.getLogger(MavenDependencyTreeResolver.class);

    private static final Pattern DEP_LINE = Pattern.compile(
            "([\\w\\-.]+):([\\w\\-.]+)(?::([\\w\\-.]+))?(?::([\\w\\-.]+))?(?::(\\w+))?");

    private final MavenDependencyTreeCache treeCache;

    public MavenDependencyTreeResolver(MavenDependencyTreeCache treeCache) {
        this.treeCache = treeCache;
    }

    public record ResolvedDependency(
            String groupId,
            String artifactId,
            String version,
            String scope,
            boolean transitive
    ) {}

    public List<ResolvedDependency> resolveModule(
            String repoLocalPath, String commitSha, String relativePomPath, int timeoutSeconds) {
        if (repoLocalPath == null || repoLocalPath.isBlank()) {
            return List.of();
        }
        return treeCache.getOrResolve(
                repoLocalPath,
                commitSha,
                relativePomPath,
                () -> timeoutSeconds,
                this);
    }

    List<ResolvedDependency> resolveModuleUncached(String repoLocalPath, String relativePomPath, int timeoutSeconds) {
        if (repoLocalPath == null || repoLocalPath.isBlank()) {
            return List.of();
        }
        Path pom = Path.of(repoLocalPath).resolve(relativePomPath);
        if (!Files.isRegularFile(pom)) {
            return List.of();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-f", pom.toString(), "-q", "dependency:tree",
                    "-DoutputType=text", "-Dscope=runtime");
            pb.directory(Path.of(repoLocalPath).toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("mvn dependency:tree timed out for {}", relativePomPath);
                return List.of();
            }
            if (process.exitValue() != 0) {
                log.warn("mvn dependency:tree failed (exit {}) for {}", process.exitValue(), relativePomPath);
                return List.of();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseTreeText(output);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("mvn dependency:tree error for {}: {}", relativePomPath, ex.getMessage());
            return List.of();
        }
    }

    static List<ResolvedDependency> parseTreeText(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Map<String, ResolvedDependency> deduped = new LinkedHashMap<>();
        boolean sawRoot = false;
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String coordPart = stripTreePrefix(line);
            if (coordPart == null) {
                continue;
            }
            Matcher m = DEP_LINE.matcher(coordPart);
            if (!m.find()) {
                continue;
            }
            String groupId = m.group(1);
            String artifactId = m.group(2);
            String packaging = m.group(3);
            String version = m.group(4);
            String scope = m.group(5);
            if (version == null || version.isBlank()) {
                continue;
            }
            if ("pom".equalsIgnoreCase(packaging) && scope == null && !sawRoot) {
                sawRoot = true;
                continue;
            }
            if (!sawRoot && scope == null && rawLine.indexOf("+-") < 0 && rawLine.indexOf("\\-") < 0) {
                sawRoot = true;
                continue;
            }
            if (scope == null || scope.isBlank()) {
                scope = "compile";
            }
            boolean transitive = sawRoot && (rawLine.contains("+-") || rawLine.contains("\\-"));
            String key = groupId + ":" + artifactId + ":" + scope + ":" + version;
            deduped.put(key, new ResolvedDependency(groupId, artifactId, version, scope, transitive));
        }
        return new ArrayList<>(deduped.values());
    }

    private static String stripTreePrefix(String line) {
        int idx = line.indexOf(':');
        if (idx < 0) {
            return null;
        }
        String trimmed = line;
        while (trimmed.startsWith("+- ") || trimmed.startsWith("\\- ") || trimmed.startsWith("|  ")) {
            if (trimmed.startsWith("|  ")) {
                trimmed = trimmed.substring(3);
            } else {
                trimmed = trimmed.substring(3);
            }
        }
        if (trimmed.startsWith("[INFO] ")) {
            trimmed = trimmed.substring(7).trim();
        }
        return trimmed;
    }
}

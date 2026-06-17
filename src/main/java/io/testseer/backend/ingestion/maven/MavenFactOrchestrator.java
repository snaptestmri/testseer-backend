package io.testseer.backend.ingestion.maven;

import io.testseer.backend.config.MavenProperties;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import io.testseer.backend.ingestion.PomFileFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Service
public class MavenFactOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MavenFactOrchestrator.class);

    private final MavenDependencyTreeResolver treeResolver;
    private final InternalArtifactLinker artifactLinker;
    private final MavenProperties mavenProperties;
    private final PomFileFetcher pomFileFetcher;
    private final MavenRepoFactsCache repoFactsCache;
    private final MavenTreeResolutionExecutor treeExecutor;

    public MavenFactOrchestrator(
            MavenDependencyTreeResolver treeResolver,
            InternalArtifactLinker artifactLinker,
            MavenProperties mavenProperties,
            PomFileFetcher pomFileFetcher,
            MavenRepoFactsCache repoFactsCache,
            MavenTreeResolutionExecutor treeExecutor) {
        this.treeResolver = treeResolver;
        this.artifactLinker = artifactLinker;
        this.mavenProperties = mavenProperties;
        this.pomFileFetcher = pomFileFetcher;
        this.repoFactsCache = repoFactsCache;
        this.treeExecutor = treeExecutor;
    }

    public MavenFacts build(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String buildTool,
            List<GitHubSourceFetcher.FetchedFile> pomFiles,
            String repoLocalPath) {
        return build(orgId, repo, serviceId, commitSha, buildTool, pomFiles, repoLocalPath, null);
    }

    public MavenFacts build(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String buildTool,
            List<GitHubSourceFetcher.FetchedFile> pomFiles,
            String repoLocalPath,
            MavenIndexOptions options) {

        if (buildTool == null || !"MAVEN".equalsIgnoreCase(buildTool)) {
            return MavenFacts.empty();
        }

        MavenIndexOptions effective = options != null ? options : MavenIndexOptions.defaults(mavenProperties);
        List<String> pomRoots = MavenModulePathResolver.normalizePomRoots(effective.pomRoots());
        boolean treeEnabled = effective.treeResolutionEnabled();

        if (repoLocalPath != null && !repoLocalPath.isBlank()) {
            MavenRepoFactsCache.CacheKey cacheKey =
                    MavenRepoFactsCache.CacheKey.of(orgId, repo, commitSha, repoLocalPath, treeEnabled);
            MavenRepoFactsCache.MavenRepoFactsBuild build = repoFactsCache.getOrBuild(
                    cacheKey, () -> buildFullRepo(orgId, repo, commitSha, repoLocalPath, treeEnabled));
            return attributeSlice(orgId, repo, serviceId, commitSha, pomRoots, build);
        }

        return buildFromPomFiles(orgId, repo, serviceId, commitSha, pomFiles, null, treeEnabled, pomRoots);
    }

    private MavenRepoFactsCache.MavenRepoFactsBuild buildFullRepo(
            String orgId,
            String repo,
            String commitSha,
            String repoLocalPath,
            boolean treeEnabled) {
        List<GitHubSourceFetcher.FetchedFile> allPoms = pomFileFetcher.fetchFromDirectory(repoLocalPath);
        return buildRepoSnapshot(orgId, repo, commitSha, repoLocalPath, allPoms, treeEnabled);
    }

    private MavenRepoFactsCache.MavenRepoFactsBuild buildRepoSnapshot(
            String orgId,
            String repo,
            String commitSha,
            String repoLocalPath,
            List<GitHubSourceFetcher.FetchedFile> pomFiles,
            boolean treeEnabled) {

        List<PomStructureExtractor.PomInput> inputs = pomFiles.stream()
                .map(f -> new PomStructureExtractor.PomInput(f.path(), f.content()))
                .toList();
        List<PomStructureExtractor.ParsedPom> parsed = PomStructureExtractor.extractAll(inputs);
        if (parsed.isEmpty()) {
            return new MavenRepoFactsCache.MavenRepoFactsBuild(List.of(), List.of(), List.of(), List.of());
        }

        Set<String> localModuleGavs = buildLocalModuleGavs(parsed);
        List<MavenRepoFactsCache.ScopedModule> modules = new ArrayList<>();
        List<MavenRepoFactsCache.ScopedDependency> dependencies = new ArrayList<>();
        Set<String> containsEdges = new LinkedHashSet<>();

        List<PomStructureExtractor.ParsedPom> leafModules = new ArrayList<>();
        Map<String, Integer> moduleIndexByPath = new LinkedHashMap<>();

        for (PomStructureExtractor.ParsedPom pom : parsed) {
            moduleIndexByPath.put(pom.modulePath(), modules.size());
            modules.add(new MavenRepoFactsCache.ScopedModule(pom, "DECLARED_ONLY"));

            for (String child : pom.childModules()) {
                containsEdges.add(pom.modulePath() + "->" + child);
            }

            for (PomStructureExtractor.DeclaredDependency dep : pom.dependencies()) {
                dependencies.add(toScopedDeclaredDep(pom.modulePath(), dep));
            }

            boolean leaf = !"pom".equalsIgnoreCase(pom.packaging()) || pom.dependencies().isEmpty();
            if (leaf) {
                leafModules.add(pom);
            }
        }

        if (treeEnabled && repoLocalPath != null && !repoLocalPath.isBlank() && !leafModules.isEmpty()) {
            int moduleBudget = mavenProperties.getMaxModulesPerIndex();
            List<PomStructureExtractor.ParsedPom> toResolve = leafModules.size() <= moduleBudget
                    ? leafModules
                    : leafModules.subList(0, moduleBudget);
            if (leafModules.size() > moduleBudget) {
                log.warn("Maven tree resolution capped at {} of {} leaf modules for {}/{}",
                        moduleBudget, leafModules.size(), orgId, repo);
            }

            List<TreeResolutionResult> results = treeExecutor.invokeAll(
                    toResolve.stream()
                            .map(pom -> (Callable<TreeResolutionResult>) () -> resolveTreeForModule(
                                    repoLocalPath, commitSha, pom))
                            .toList());

            for (TreeResolutionResult result : results) {
                Integer idx = moduleIndexByPath.get(result.modulePath());
                if (idx == null) {
                    continue;
                }
                MavenRepoFactsCache.ScopedModule existing = modules.get(idx);
                if (!result.resolved().isEmpty()) {
                    modules.set(idx, new MavenRepoFactsCache.ScopedModule(existing.pom(), "RESOLVED"));
                    for (MavenDependencyTreeResolver.ResolvedDependency rd : result.resolved()) {
                        dependencies.add(toScopedResolvedDep(result.modulePath(), rd));
                    }
                } else if (repoLocalPath != null && !repoLocalPath.isBlank()) {
                    modules.set(idx, new MavenRepoFactsCache.ScopedModule(existing.pom(), "SKIPPED"));
                }
            }
        }

        return new MavenRepoFactsCache.MavenRepoFactsBuild(
                parsed,
                List.copyOf(modules),
                dedupeScopedDependencies(dependencies),
                List.copyOf(containsEdges));
    }

    private record TreeResolutionResult(String modulePath, List<MavenDependencyTreeResolver.ResolvedDependency> resolved) {}

    private TreeResolutionResult resolveTreeForModule(
            String repoLocalPath, String commitSha, PomStructureExtractor.ParsedPom pom) {
        List<MavenDependencyTreeResolver.ResolvedDependency> resolved = treeResolver.resolveModule(
                repoLocalPath, commitSha, pom.relativePomPath(), mavenProperties.getTreeTimeoutSeconds());
        return new TreeResolutionResult(pom.modulePath(), resolved);
    }

    private MavenFacts buildFromPomFiles(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            List<GitHubSourceFetcher.FetchedFile> pomFiles,
            String repoLocalPath,
            boolean treeEnabled,
            List<String> pomRoots) {
        if (pomFiles == null || pomFiles.isEmpty()) {
            return MavenFacts.empty();
        }
        MavenRepoFactsCache.MavenRepoFactsBuild build =
                buildRepoSnapshot(orgId, repo, commitSha, repoLocalPath, pomFiles, treeEnabled);
        return attributeSlice(orgId, repo, serviceId, commitSha, pomRoots, build);
    }

    private MavenFacts attributeSlice(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            List<String> pomRoots,
            MavenRepoFactsCache.MavenRepoFactsBuild build) {

        InternalArtifactLinker.LinkIndex linkIndex = artifactLinker.buildLinkIndex(orgId);
        Set<String> localModuleGavs = buildLocalModuleGavs(build.parsedPoms());

        List<FactBatch.MavenModuleFact> modules = new ArrayList<>();
        for (MavenRepoFactsCache.ScopedModule module : build.modules()) {
            if (!MavenModulePathResolver.moduleInScope(module.pom().modulePath(), pomRoots)) {
                continue;
            }
            modules.add(toModuleFact(
                    orgId, repo, serviceId, commitSha, module.pom(), module.resolutionStatus()));
        }

        List<FactBatch.MavenDependencyFact> dependencies = new ArrayList<>();
        for (MavenRepoFactsCache.ScopedDependency dep : build.dependencies()) {
            if (!MavenModulePathResolver.moduleInScope(dep.fromModulePath(), pomRoots)) {
                continue;
            }
            dependencies.add(toAttributedDep(orgId, repo, serviceId, commitSha, dep, linkIndex, localModuleGavs));
        }

        List<String> containsEdges = build.containsModuleEdges().stream()
                .filter(edge -> edgeInScope(edge, pomRoots))
                .toList();

        return new MavenFacts(
                dedupeModules(modules),
                dedupeDependencies(dependencies),
                containsEdges);
    }

    private static boolean edgeInScope(String edge, List<String> pomRoots) {
        if (pomRoots == null || pomRoots.isEmpty()) {
            return true;
        }
        int arrow = edge.indexOf("->");
        if (arrow < 0) {
            return true;
        }
        String parent = edge.substring(0, arrow);
        String child = edge.substring(arrow + 2);
        return MavenModulePathResolver.moduleInScope(parent, pomRoots)
                && MavenModulePathResolver.moduleInScope(child, pomRoots);
    }

    private static Set<String> buildLocalModuleGavs(List<PomStructureExtractor.ParsedPom> parsed) {
        return parsed.stream()
                .filter(p -> p.groupId() != null && p.artifactId() != null)
                .map(p -> InternalArtifactLinker.gavKey(p.groupId(), p.artifactId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static FactBatch.MavenModuleFact toModuleFact(
            String orgId, String repo, String serviceId, String commitSha,
            PomStructureExtractor.ParsedPom pom, String resolutionStatus) {
        return new FactBatch.MavenModuleFact(
                orgId, repo, serviceId, commitSha,
                pom.modulePath(),
                pom.relativePomPath(),
                pom.groupId(),
                pom.artifactId(),
                pom.version(),
                pom.packaging(),
                pom.parentGroupId(),
                pom.parentArtifactId(),
                pom.parentVersion(),
                pom.rootModule(),
                resolutionStatus,
                "pom-xml",
                0.95
        );
    }

    private static MavenRepoFactsCache.ScopedDependency toScopedDeclaredDep(
            String modulePath, PomStructureExtractor.DeclaredDependency dep) {
        boolean unresolved = PomStructureExtractor.isUnresolvedVersion(dep.versionLiteral());
        String version = unresolved ? null : dep.versionLiteral();
        String reason = PomStructureExtractor.unresolvedReason(dep.versionLiteral());
        return new MavenRepoFactsCache.ScopedDependency(
                modulePath,
                dep.groupId(),
                dep.artifactId(),
                version,
                dep.versionLiteral(),
                dep.scope(),
                dep.optional(),
                false,
                !unresolved && version != null,
                reason,
                "pom-xml");
    }

    private static MavenRepoFactsCache.ScopedDependency toScopedResolvedDep(
            String modulePath, MavenDependencyTreeResolver.ResolvedDependency dep) {
        return new MavenRepoFactsCache.ScopedDependency(
                modulePath,
                dep.groupId(),
                dep.artifactId(),
                dep.version(),
                dep.version(),
                dep.scope(),
                false,
                dep.transitive(),
                true,
                null,
                "mvn-dependency-tree");
    }

    private FactBatch.MavenDependencyFact toAttributedDep(
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            MavenRepoFactsCache.ScopedDependency dep,
            InternalArtifactLinker.LinkIndex linkIndex,
            Set<String> localModuleGavs) {
        LinkFields link = resolveLink(
                orgId, serviceId, dep.groupId(), dep.artifactId(), linkIndex, localModuleGavs);
        return new FactBatch.MavenDependencyFact(
                orgId, repo, serviceId, commitSha,
                dep.fromModulePath(),
                dep.groupId(),
                dep.artifactId(),
                dep.version(),
                dep.versionLiteral(),
                dep.scope(),
                dep.optional(),
                dep.transitive(),
                dep.resolved(),
                dep.unresolvedReason(),
                link.linkedServiceId(),
                link.linkedRepo(),
                link.linkSource(),
                link.crossRepo(),
                dep.evidenceSource(),
                link.confidence());
    }

    private LinkFields resolveLink(
            String orgId,
            String serviceId,
            String groupId,
            String artifactId,
            InternalArtifactLinker.LinkIndex linkIndex,
            Set<String> localModuleGavs) {

        Optional<InternalArtifactLinker.ArtifactLink> link = artifactLinker.resolve(
                orgId, serviceId, groupId, artifactId, linkIndex, localModuleGavs);
        if (link.isEmpty()) {
            return LinkFields.empty();
        }
        InternalArtifactLinker.ArtifactLink resolved = link.get();
        boolean crossRepo = !resolved.serviceId().equals(serviceId);
        return new LinkFields(
                resolved.serviceId(),
                resolved.repo(),
                resolved.source().name(),
                crossRepo,
                resolved.confidence());
    }

    private record LinkFields(
            String linkedServiceId,
            String linkedRepo,
            String linkSource,
            boolean crossRepo,
            double confidence) {

        static LinkFields empty() {
            return new LinkFields(null, null, null, false, 0.90);
        }
    }

    private static List<MavenRepoFactsCache.ScopedDependency> dedupeScopedDependencies(
            List<MavenRepoFactsCache.ScopedDependency> deps) {
        Map<String, MavenRepoFactsCache.ScopedDependency> map = new LinkedHashMap<>();
        for (MavenRepoFactsCache.ScopedDependency d : deps) {
            String versionKey = d.version() != null ? d.version() : d.versionLiteral();
            String key = d.fromModulePath() + "|" + d.groupId() + "|" + d.artifactId()
                    + "|" + d.scope() + "|" + d.transitive() + "|" + versionKey;
            map.merge(key, d, (a, b) -> b.resolved() ? b : a);
        }
        return List.copyOf(map.values());
    }

    private static List<FactBatch.MavenModuleFact> dedupeModules(List<FactBatch.MavenModuleFact> modules) {
        Map<String, FactBatch.MavenModuleFact> map = new LinkedHashMap<>();
        for (FactBatch.MavenModuleFact m : modules) {
            map.put(m.modulePath(), m);
        }
        return List.copyOf(map.values());
    }

    private static List<FactBatch.MavenDependencyFact> dedupeDependencies(List<FactBatch.MavenDependencyFact> deps) {
        Map<String, FactBatch.MavenDependencyFact> map = new LinkedHashMap<>();
        for (FactBatch.MavenDependencyFact d : deps) {
            String versionKey = d.toVersion() != null ? d.toVersion() : d.versionLiteral();
            String key = d.fromModulePath() + "|" + d.toGroupId() + "|" + d.toArtifactId()
                    + "|" + d.scope() + "|" + d.transitive() + "|" + versionKey;
            map.merge(key, d, MavenFactOrchestrator::preferDependency);
        }
        return List.copyOf(map.values());
    }

    private static FactBatch.MavenDependencyFact preferDependency(
            FactBatch.MavenDependencyFact a, FactBatch.MavenDependencyFact b) {
        if (a.linkedServiceId() != null && b.linkedServiceId() == null) {
            return a;
        }
        if (b.linkedServiceId() != null && a.linkedServiceId() == null) {
            return b;
        }
        return b.resolved() ? b : a;
    }

    public record MavenFacts(
            List<FactBatch.MavenModuleFact> modules,
            List<FactBatch.MavenDependencyFact> dependencies,
            List<String> containsModuleEdges
    ) {
        public static MavenFacts empty() {
            return new MavenFacts(List.of(), List.of(), List.of());
        }
    }
}

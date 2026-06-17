package io.testseer.backend.config;

import io.testseer.backend.config.workspace.OrgWorkspaceConfigResolver;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Resolves multi-module catalog profiles and pinned library joins (YAML bootstrap + org DB). */
@Service
public class WorkspaceCatalogService {

    private final WorkspaceConfig yamlConfig;
    private final OrgWorkspaceConfigResolver configResolver;

    public WorkspaceCatalogService(
            WorkspaceConfigLoader loader,
            OrgWorkspaceConfigResolver configResolver) {
        this.yamlConfig = loader.getConfig();
        this.configResolver = configResolver;
    }

    /** YAML-only config (legacy). Prefer {@link #config(String)} for org-scoped resolution. */
    public WorkspaceConfig config() {
        return yamlConfig;
    }

    public WorkspaceConfig config(String orgId) {
        return configResolver.resolve(orgId);
    }

    public Optional<WorkspaceConfig.CatalogLibraryConfig> findCatalogLibrary(String orgId, String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        Optional<WorkspaceConfig.CatalogLibraryConfig> resolved = findInConfig(config(orgId), id);
        if (resolved.isPresent()) {
            return Optional.of(overlayYamlCatalogFlags(resolved.get()));
        }
        return findInYaml(id);
    }

    public Optional<WorkspaceConfig.CatalogLibraryConfig> findCatalogLibraryByRepo(String orgId, String repo) {
        if (repo == null || repo.isBlank()) return Optional.empty();
        Optional<WorkspaceConfig.CatalogLibraryConfig> resolved = findByRepoInConfig(config(orgId), repo);
        if (resolved.isPresent()) {
            return Optional.of(overlayYamlCatalogFlags(resolved.get()));
        }
        return findByRepoInYaml(repo);
    }

    private Optional<WorkspaceConfig.CatalogLibraryConfig> findInConfig(WorkspaceConfig config, String id) {
        if (config.catalogLibraries() == null) return Optional.empty();
        return config.catalogLibraries().stream()
                .filter(c -> id.equals(c.id()))
                .findFirst();
    }

    private Optional<WorkspaceConfig.CatalogLibraryConfig> findByRepoInConfig(WorkspaceConfig config, String repo) {
        if (config.catalogLibraries() == null) return Optional.empty();
        return config.catalogLibraries().stream()
                .filter(c -> repo.equals(c.repo()))
                .findFirst();
    }

    private Optional<WorkspaceConfig.CatalogLibraryConfig> findInYaml(String id) {
        if (yamlConfig == null) return Optional.empty();
        return findInConfig(yamlConfig, id);
    }

    private Optional<WorkspaceConfig.CatalogLibraryConfig> findByRepoInYaml(String repo) {
        if (yamlConfig == null) return Optional.empty();
        return findByRepoInConfig(yamlConfig, repo);
    }

    /** DB-backed catalog rows omit indexOpenApi; preserve YAML flags for bootstrap entries. */
    private WorkspaceConfig.CatalogLibraryConfig overlayYamlCatalogFlags(
            WorkspaceConfig.CatalogLibraryConfig resolved) {
        return findInYaml(resolved.id())
                .filter(yaml -> yaml.indexOpenApi() && !resolved.indexOpenApi())
                .map(yaml -> new WorkspaceConfig.CatalogLibraryConfig(
                        resolved.id(),
                        resolved.repo(),
                        resolved.serviceName(),
                        resolved.sourceRoots(),
                        resolved.indexDdl(),
                        yaml.indexOpenApi()))
                .orElse(resolved);
    }

    public Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModule(String orgId, String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        Optional<WorkspaceConfig.ServiceModuleConfig> resolved = findServiceModuleInConfig(config(orgId), id);
        if (resolved.isPresent()) {
            return resolved;
        }
        return findServiceModuleInYaml(id);
    }

    public Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModuleByRepo(String orgId, String repo) {
        if (repo == null || repo.isBlank()) return Optional.empty();
        Optional<WorkspaceConfig.ServiceModuleConfig> resolved = findServiceModuleByRepoInConfig(config(orgId), repo);
        if (resolved.isPresent()) {
            return resolved;
        }
        return findServiceModuleByRepoInYaml(repo);
    }

    private Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModuleInConfig(
            WorkspaceConfig config, String id) {
        if (config.serviceModules() == null) return Optional.empty();
        return config.serviceModules().stream()
                .filter(s -> id.equals(s.id()))
                .findFirst();
    }

    private Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModuleByRepoInConfig(
            WorkspaceConfig config, String repo) {
        if (config.serviceModules() == null) return Optional.empty();
        return config.serviceModules().stream()
                .filter(s -> repo.equals(s.repo()))
                .findFirst();
    }

    private Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModuleInYaml(String id) {
        if (yamlConfig == null) return Optional.empty();
        return findServiceModuleInConfig(yamlConfig, id);
    }

    private Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModuleByRepoInYaml(String repo) {
        if (yamlConfig == null) return Optional.empty();
        return findServiceModuleByRepoInConfig(yamlConfig, repo);
    }

    /** Profile for local index: explicit ids win; else catalog library by repo; else service module; else legacy. */
    public Optional<IndexProfile> resolveIndexProfile(
            String orgId,
            String repoFolderName,
            String catalogLibraryId,
            String serviceModuleId) {
        if (catalogLibraryId != null && !catalogLibraryId.isBlank()) {
            Optional<IndexProfile> byId = findCatalogLibrary(orgId, catalogLibraryId).map(this::toCatalogProfile);
            if (byId.isPresent()) return byId;
        }
        if (serviceModuleId != null && !serviceModuleId.isBlank()) {
            Optional<IndexProfile> byModule = findServiceModule(orgId, serviceModuleId).map(this::toServiceProfile);
            if (byModule.isPresent()) return byModule;
        }
        return resolveRepoProfile(orgId, repoFolderName);
    }

    /** Profile for local index: first catalog library for repo, else service module, else empty. */
    public Optional<IndexProfile> resolveRepoProfile(String orgId, String repoFolderName) {
        Optional<WorkspaceConfig.CatalogLibraryConfig> catalog = findCatalogLibraryByRepo(orgId, repoFolderName);
        if (catalog.isPresent()) {
            return Optional.of(toCatalogProfile(catalog.get()));
        }
        Optional<WorkspaceConfig.ServiceModuleConfig> service = findServiceModuleByRepo(orgId, repoFolderName);
        if (service.isPresent()) {
            return Optional.of(toServiceProfile(service.get()));
        }
        return Optional.empty();
    }

    private IndexProfile toCatalogProfile(WorkspaceConfig.CatalogLibraryConfig c) {
        return new IndexProfile(
                c.id(),
                c.repo(),
                c.serviceName() != null ? c.serviceName() : c.id(),
                "library",
                c.sourceRoots(),
                c.indexDdl(),
                c.indexOpenApi(),
                List.of(),
                List.of()
        );
    }

    private IndexProfile toServiceProfile(WorkspaceConfig.ServiceModuleConfig s) {
        return new IndexProfile(
                s.id(),
                s.repo(),
                s.id(),
                "service",
                s.sourceRoots(),
                false,
                false,
                pinnedCatalogLibraryIds(s, null),
                s.configRoots()
        );
    }

    public List<String> pinnedCatalogLibraryIds(WorkspaceConfig.ServiceModuleConfig module) {
        return pinnedCatalogLibraryIds(module, null);
    }

    public List<String> pinnedCatalogLibraryIds(WorkspaceConfig.ServiceModuleConfig module, String orgId) {
        Set<String> ids = new LinkedHashSet<>();
        if (module.symbolClasspath() != null) {
            for (WorkspaceConfig.SymbolClasspathEntry entry : module.symbolClasspath()) {
                if (entry.catalogLibrary() != null && !entry.catalogLibrary().isBlank()) {
                    ids.add(entry.catalogLibrary());
                }
            }
        }
        if (ids.isEmpty() && orgId != null) {
            findCatalogLibrary(orgId, "platform-data").ifPresent(c -> ids.add(c.id()));
        } else if (ids.isEmpty()) {
            if (yamlConfig.catalogLibraries() != null) {
                yamlConfig.catalogLibraries().stream()
                        .filter(c -> "platform-data".equals(c.id()))
                        .findFirst()
                        .ifPresent(c -> ids.add(c.id()));
            }
        }
        return List.copyOf(ids);
    }

    public List<String> pinnedCatalogLibraryIdsForService(String orgId, String serviceModuleId) {
        return findServiceModule(orgId, serviceModuleId)
                .map(m -> pinnedCatalogLibraryIds(m, orgId))
                .orElse(List.of());
    }

    public List<String> bundleIndexTargets(String orgId, String bundleName) {
        WorkspaceConfig config = config(orgId);
        String resolved = config.resolveBundleName(bundleName);
        WorkspaceConfig.BundleConfig bundle = config.bundles() != null
                ? config.bundles().get(resolved) : null;
        if (bundle == null) return List.of();

        if (bundle.indexOrder() != null && !bundle.indexOrder().isEmpty()) {
            List<String> targets = new ArrayList<>();
            for (WorkspaceConfig.BundleIndexEntry entry : bundle.indexOrder()) {
                if (entry.catalogLibrary() != null) {
                    targets.add(entry.catalogLibrary());
                } else if (entry.serviceModule() != null) {
                    targets.add(entry.serviceModule());
                } else if (entry.repo() != null) {
                    targets.add(entry.repo());
                }
            }
            return targets;
        }
        return bundle.repos() != null ? bundle.repos() : List.of();
    }

    public Path resolveGithubRoot() {
        return resolveGithubRoot(null);
    }

    public Path resolveGithubRoot(String orgId) {
        String dir = orgId != null ? config(orgId).githubDir() : yamlConfig.githubDir();
        if (dir == null || dir.isBlank()) return null;
        String expanded = dir.startsWith("~")
                ? System.getProperty("user.home") + dir.substring(1)
                : dir;
        return Path.of(expanded);
    }

    public record IndexProfile(
            String serviceId,
            String repo,
            String serviceName,
            String moduleType,
            List<String> sourceRoots,
            boolean indexDdl,
            boolean indexOpenApi,
            List<String> pinnedCatalogLibraryIds,
            List<String> configRoots
    ) {
        public IndexProfile {
            if (configRoots == null) {
                configRoots = List.of();
            }
        }
    }
}

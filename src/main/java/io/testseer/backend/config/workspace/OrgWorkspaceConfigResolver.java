package io.testseer.backend.config.workspace;

import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.config.WorkspaceConfigLoader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves org-scoped workspace config: DB overrides YAML bootstrap for default org. */
@Service
public class OrgWorkspaceConfigResolver {

    private final WorkspaceConfigLoader yamlLoader;
    private final OrgWorkspaceConfigRepository repository;

    public OrgWorkspaceConfigResolver(
            WorkspaceConfigLoader yamlLoader,
            OrgWorkspaceConfigRepository repository) {
        this.yamlLoader = yamlLoader;
        this.repository = repository;
    }

    public WorkspaceConfig resolve(String orgId) {
        WorkspaceConfig yaml = yamlLoader.getConfig();
        String effectiveOrg = normalizeOrgId(orgId, yaml);

        String githubDir = repository.findOrgSettings(effectiveOrg)
                .map(OrgWorkspaceConfigRepository.OrgSettingsRow::githubDir)
                .orElse(yaml.githubDir());
        String defaultBundle = repository.findOrgSettings(effectiveOrg)
                .map(OrgWorkspaceConfigRepository.OrgSettingsRow::defaultBundle)
                .orElse(yaml.defaultBundle());

        List<WorkspaceConfig.CatalogLibraryConfig> catalogLibraries = resolveCatalogLibraries(effectiveOrg, yaml);
        List<WorkspaceConfig.ServiceModuleConfig> serviceModules = resolveServiceModules(effectiveOrg, yaml);
        Map<String, WorkspaceConfig.BundleConfig> bundles = resolveBundles(effectiveOrg, yaml);

        return new WorkspaceConfig(
                githubDir,
                effectiveOrg,
                defaultBundle,
                yaml.repos(),
                catalogLibraries,
                serviceModules,
                bundles
        );
    }

    private List<WorkspaceConfig.CatalogLibraryConfig> resolveCatalogLibraries(
            String orgId, WorkspaceConfig yaml) {
        List<WorkspaceConfig.CatalogLibraryConfig> yamlLibs = yamlCatalogLibraries(orgId, yaml);
        if (!repository.hasCatalogLibraries(orgId)) {
            return yamlLibs;
        }
        return mergeById(
                repository.listCatalogLibraries(orgId),
                yamlLibs,
                WorkspaceConfig.CatalogLibraryConfig::id);
    }

    private List<WorkspaceConfig.ServiceModuleConfig> resolveServiceModules(
            String orgId, WorkspaceConfig yaml) {
        List<WorkspaceConfig.ServiceModuleConfig> yamlModules = yamlServiceModules(orgId, yaml);
        if (!repository.hasServiceModules(orgId)) {
            return yamlModules;
        }
        return mergeById(
                repository.listServiceModules(orgId),
                yamlModules,
                WorkspaceConfig.ServiceModuleConfig::id);
    }

    private Map<String, WorkspaceConfig.BundleConfig> resolveBundles(String orgId, WorkspaceConfig yaml) {
        Map<String, WorkspaceConfig.BundleConfig> yamlBundles = yamlBundles(orgId, yaml);
        if (!repository.hasBundles(orgId)) {
            return yamlBundles;
        }
        Map<String, WorkspaceConfig.BundleConfig> merged = new LinkedHashMap<>(repository.listBundles(orgId));
        yamlBundles.forEach(merged::putIfAbsent);
        return merged;
    }

    private List<WorkspaceConfig.CatalogLibraryConfig> yamlCatalogLibraries(String orgId, WorkspaceConfig yaml) {
        if (orgId.equals(yaml.defaultOrgId()) && yaml.catalogLibraries() != null) {
            return yaml.catalogLibraries();
        }
        return List.of();
    }

    private List<WorkspaceConfig.ServiceModuleConfig> yamlServiceModules(String orgId, WorkspaceConfig yaml) {
        if (orgId.equals(yaml.defaultOrgId()) && yaml.serviceModules() != null) {
            return yaml.serviceModules();
        }
        return List.of();
    }

    private Map<String, WorkspaceConfig.BundleConfig> yamlBundles(String orgId, WorkspaceConfig yaml) {
        if (orgId.equals(yaml.defaultOrgId()) && yaml.bundles() != null) {
            return yaml.bundles();
        }
        return Map.of();
    }

    /** DB rows win on id collision; YAML fills gaps after a partial import. */
    private static <T> List<T> mergeById(List<T> primary, List<T> secondary, Function<T, String> idFn) {
        Set<String> seen = primary.stream().map(idFn).collect(Collectors.toCollection(LinkedHashSet::new));
        List<T> merged = new ArrayList<>(primary);
        for (T item : secondary) {
            if (seen.add(idFn.apply(item))) {
                merged.add(item);
            }
        }
        return merged;
    }

    private static String normalizeOrgId(String orgId, WorkspaceConfig yaml) {
        if (orgId != null && !orgId.isBlank()) return orgId;
        return yaml.defaultOrgId() != null ? yaml.defaultOrgId() : "default";
    }
}

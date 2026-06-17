package io.testseer.backend.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record WorkspaceConfig(
        String githubDir,
        String defaultOrgId,
        String defaultBundle,
        List<String> repos,
        List<CatalogLibraryConfig> catalogLibraries,
        List<ServiceModuleConfig> serviceModules,
        Map<String, BundleConfig> bundles
) {
    public record CatalogLibraryConfig(
            String id,
            String repo,
            String serviceName,
            List<String> sourceRoots,
            boolean indexDdl,
            boolean indexOpenApi
    ) {}

    public record ServiceModuleConfig(
            String id,
            String repo,
            List<String> sourceRoots,
            List<SymbolClasspathEntry> symbolClasspath,
            List<String> configRoots
    ) {
        public ServiceModuleConfig {
            if (configRoots == null) {
                configRoots = List.of();
            }
        }

        public ServiceModuleConfig(
                String id,
                String repo,
                List<String> sourceRoots,
                List<SymbolClasspathEntry> symbolClasspath) {
            this(id, repo, sourceRoots, symbolClasspath, List.of());
        }
    }

    public record SymbolClasspathEntry(
            String catalogLibrary,
            String repo,
            List<String> roots
    ) {}

    public record BundleConfig(
            List<String> repos,
            List<BundleIndexEntry> indexOrder,
            TraceConfig trace,
            List<PropagationEdgeConfig> propagationEdges
    ) {}

    public record PropagationEdgeConfig(
            String id,
            String pattern,
            PropagationStoreRef authoritative,
            List<PropagationPeripheralConfig> peripheral,
            List<PropagationConsumerConfig> consumers,
            List<String> correlationKeys,
            PropagationPollStrategy pollStrategy
    ) {}

    public record PropagationStoreRef(
            String serviceId,
            String storeType,
            String physicalName
    ) {}

    public record PropagationPeripheralConfig(
            String serviceId,
            String storeType,
            String physicalName,
            String lagClass
    ) {}

    public record PropagationConsumerConfig(
            String serviceId,
            String flowStep
    ) {}

    public record PropagationPollStrategy(
            List<String> order,
            String primaryPollHint,
            List<String> notes
    ) {}

    public record BundleIndexEntry(
            String catalogLibrary,
            String serviceModule,
            String repo
    ) {}

    public record TraceConfig(String shortId, String env) {}

    public List<String> bundleRepos(String bundleName) {
        if (bundles == null || bundleName == null || bundleName.isBlank()) {
            return List.of();
        }
        BundleConfig bundle = bundles.get(bundleName);
        if (bundle == null) return List.of();
        if (bundle.indexOrder() != null && !bundle.indexOrder().isEmpty()) {
            return bundle.indexOrder().stream()
                    .map(e -> firstNonBlank(e.repo(), e.serviceModule(), e.catalogLibrary()))
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
        }
        return bundle.repos() != null ? bundle.repos() : List.of();
    }

    public String resolveBundleName(String bundleName) {
        if (bundleName != null && !bundleName.isBlank()) {
            return bundleName;
        }
        return defaultBundle;
    }

    public Optional<CatalogLibraryConfig> catalogLibrary(String id) {
        if (catalogLibraries == null || id == null) return Optional.empty();
        return catalogLibraries.stream().filter(c -> id.equals(c.id())).findFirst();
    }

    public Optional<ServiceModuleConfig> serviceModule(String id) {
        if (serviceModules == null || id == null) return Optional.empty();
        return serviceModules.stream().filter(s -> id.equals(s.id())).findFirst();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}

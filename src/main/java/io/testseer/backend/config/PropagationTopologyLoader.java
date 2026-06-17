package io.testseer.backend.config;

import org.springframework.stereotype.Component;

import java.util.List;

/** Loads propagation topology edges from workspace bundle config (CR-4). */
@Component
public class PropagationTopologyLoader {

    private final WorkspaceCatalogService workspaceCatalog;

    public PropagationTopologyLoader(WorkspaceCatalogService workspaceCatalog) {
        this.workspaceCatalog = workspaceCatalog;
    }

    public List<WorkspaceConfig.PropagationEdgeConfig> edgesForBundle(String orgId, String bundleName) {
        WorkspaceConfig config = workspaceCatalog.config(orgId);
        String resolved = config.resolveBundleName(bundleName);
        if (config.bundles() == null) {
            return List.of();
        }
        WorkspaceConfig.BundleConfig bundle = config.bundles().get(resolved);
        if (bundle == null || bundle.propagationEdges() == null) {
            return List.of();
        }
        return bundle.propagationEdges();
    }
}

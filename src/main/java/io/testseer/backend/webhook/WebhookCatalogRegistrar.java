package io.testseer.backend.webhook;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Auto-registers workspace catalog libraries when GitHub webhooks arrive (BL-046 P3). */
@Component
public class WebhookCatalogRegistrar {

    private static final Logger log = LoggerFactory.getLogger(WebhookCatalogRegistrar.class);

    private final WorkspaceCatalogService workspaceCatalog;
    private final ServiceRegistryService registryService;

    public WebhookCatalogRegistrar(
            WorkspaceCatalogService workspaceCatalog,
            ServiceRegistryService registryService) {
        this.workspaceCatalog = workspaceCatalog;
        this.registryService = registryService;
    }

    public void ensureCatalogRegistered(String orgId, String repo) {
        workspaceCatalog.findCatalogLibraryByRepo(orgId, repo).ifPresent(catalog -> {
            String serviceName = catalog.serviceName() != null ? catalog.serviceName() : catalog.id();
            if (registryService.getByOrgRepoAndName(orgId, repo, serviceName).isPresent()) {
                return;
            }
            try {
                registryService.register(new RegistrationRequest(
                        orgId,
                        repo,
                        serviceName,
                        "UNKNOWN",
                        "library",
                        catalog.sourceRoots() != null ? catalog.sourceRoots() : List.of(),
                        List.of(),
                        null
                ));
                log.info("Auto-registered catalog library {}/{} for webhook ingestion", orgId, repo);
            } catch (Exception ex) {
                log.debug("Catalog library {}/{} already registered: {}", orgId, repo, ex.getMessage());
            }
        });
    }

    static boolean isOpenApiCatalog(WorkspaceConfig.CatalogLibraryConfig catalog) {
        return catalog != null && catalog.indexOpenApi();
    }
}

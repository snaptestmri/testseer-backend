package io.testseer.backend.config.workspace;

import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.config.WorkspaceConfigLoader;
import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkspaceCatalogAdminService {

    private final OrgWorkspaceConfigRepository repository;
    private final OrgWorkspaceConfigResolver resolver;
    private final WorkspaceConfigLoader yamlLoader;
    private final ServiceRegistryService registryService;

    public WorkspaceCatalogAdminService(
            OrgWorkspaceConfigRepository repository,
            OrgWorkspaceConfigResolver resolver,
            WorkspaceConfigLoader yamlLoader,
            ServiceRegistryService registryService) {
        this.repository = repository;
        this.resolver = resolver;
        this.yamlLoader = yamlLoader;
        this.registryService = registryService;
    }

    public ResolvedWorkspaceView getResolvedConfig(String orgId) {
        WorkspaceConfig config = resolver.resolve(orgId);
        return new ResolvedWorkspaceView(
                config.defaultOrgId(),
                config.githubDir(),
                config.defaultBundle(),
                config.catalogLibraries(),
                config.serviceModules(),
                config.bundles() != null ? config.bundles().keySet().stream().sorted().toList() : List.of(),
                sourceFor(orgId)
        );
    }

    public List<WorkspaceConfig.CatalogLibraryConfig> listCatalogLibraries(String orgId) {
        return resolver.resolve(orgId).catalogLibraries();
    }

    public WorkspaceConfig.CatalogLibraryConfig getCatalogLibrary(String orgId, String libraryId) {
        return findCatalogLibraryOrThrow(orgId, libraryId);
    }

    public WorkspaceConfig.CatalogLibraryConfig createCatalogLibrary(
            String orgId, @Valid CreateCatalogLibraryRequest request) {
        if (repository.findCatalogLibrary(orgId, request.id()).isPresent()) {
            throw new DuplicateCatalogLibraryException(orgId, request.id());
        }
        repository.insertCatalogLibrary(orgId, new OrgWorkspaceConfigRepository.CatalogLibraryUpsert(
                request.id(),
                request.repo(),
                request.serviceName() != null ? request.serviceName() : request.id(),
                request.sourceRoots(),
                request.indexDdl() != null && request.indexDdl()
        ));
        return repository.findCatalogLibrary(orgId, request.id())
                .orElseThrow(() -> new CatalogLibraryNotFoundException(orgId, request.id()));
    }

    public WorkspaceConfig.CatalogLibraryConfig updateCatalogLibrary(
            String orgId, String libraryId, UpdateCatalogLibraryRequest request) {
        if (repository.findCatalogLibrary(orgId, libraryId).isEmpty()) {
            throw new CatalogLibraryNotFoundException(orgId, libraryId);
        }
        repository.updateCatalogLibrary(orgId, libraryId, new OrgWorkspaceConfigRepository.CatalogLibraryUpsert(
                libraryId,
                request.repo(),
                request.serviceName(),
                request.sourceRoots(),
                request.indexDdl()
        ));
        return repository.findCatalogLibrary(orgId, libraryId)
                .orElseThrow(() -> new CatalogLibraryNotFoundException(orgId, libraryId));
    }

    public void deleteCatalogLibrary(String orgId, String libraryId) {
        if (repository.deleteCatalogLibrary(orgId, libraryId) == 0) {
            throw new CatalogLibraryNotFoundException(orgId, libraryId);
        }
    }

    public ServiceEntry registerCatalogLibrary(String orgId, String libraryId, String buildTool) {
        WorkspaceConfig.CatalogLibraryConfig lib = findCatalogLibraryOrThrow(orgId, libraryId);
        try {
            return registryService.register(new RegistrationRequest(
                    orgId,
                    lib.repo(),
                    lib.serviceName() != null ? lib.serviceName() : lib.id(),
                    buildTool != null && !buildTool.isBlank() ? buildTool : "MAVEN",
                    "library",
                    lib.sourceRoots(),
                    List.of("src/test/java"),
                    null
            ));
        } catch (DuplicateServiceException ex) {
            return registryService.getByOrgRepoAndName(
                            orgId,
                            lib.repo(),
                            lib.serviceName() != null ? lib.serviceName() : lib.id())
                    .orElseThrow(() -> ex);
        }
    }

    public List<WorkspaceConfig.ServiceModuleConfig> listServiceModules(String orgId) {
        return resolver.resolve(orgId).serviceModules();
    }

    public WorkspaceConfig.ServiceModuleConfig getServiceModule(String orgId, String moduleId) {
        return findServiceModuleOrThrow(orgId, moduleId);
    }

    public WorkspaceConfig.ServiceModuleConfig createServiceModule(
            String orgId, @Valid CreateServiceModuleRequest request) {
        if (repository.findServiceModule(orgId, request.id()).isPresent()) {
            throw new DuplicateServiceModuleException(orgId, request.id());
        }
        validateCatalogLibraryRefs(orgId, request.catalogLibraryIds());
        repository.insertServiceModule(orgId, new OrgWorkspaceConfigRepository.ServiceModuleUpsert(
                request.id(),
                request.repo(),
                request.sourceRoots(),
                request.catalogLibraryIds()
        ));
        return repository.findServiceModule(orgId, request.id())
                .orElseThrow(() -> new ServiceModuleNotFoundException(orgId, request.id()));
    }

    public WorkspaceConfig.ServiceModuleConfig updateServiceModule(
            String orgId, String moduleId, UpdateServiceModuleRequest request) {
        if (repository.findServiceModule(orgId, moduleId).isEmpty()) {
            throw new ServiceModuleNotFoundException(orgId, moduleId);
        }
        if (request.catalogLibraryIds() != null) {
            validateCatalogLibraryRefs(orgId, request.catalogLibraryIds());
        }
        repository.updateServiceModule(orgId, moduleId, new OrgWorkspaceConfigRepository.ServiceModuleUpsert(
                moduleId,
                request.repo(),
                request.sourceRoots(),
                request.catalogLibraryIds()
        ));
        return repository.findServiceModule(orgId, moduleId)
                .orElseThrow(() -> new ServiceModuleNotFoundException(orgId, moduleId));
    }

    public void deleteServiceModule(String orgId, String moduleId) {
        if (repository.deleteServiceModule(orgId, moduleId) == 0) {
            throw new ServiceModuleNotFoundException(orgId, moduleId);
        }
    }

    public int importFromYaml(String orgId) {
        WorkspaceConfig yaml = yamlLoader.getConfig();
        if (!orgId.equals(yaml.defaultOrgId())) {
            throw new IllegalArgumentException(
                    "YAML import is only supported for defaultOrgId=" + yaml.defaultOrgId());
        }
        int imported = 0;
        if (yaml.githubDir() != null || yaml.defaultBundle() != null) {
            repository.upsertOrgSettings(orgId, yaml.githubDir(), yaml.defaultBundle());
        }
        if (yaml.catalogLibraries() != null) {
            for (WorkspaceConfig.CatalogLibraryConfig lib : yaml.catalogLibraries()) {
                var upsert = new OrgWorkspaceConfigRepository.CatalogLibraryUpsert(
                        lib.id(), lib.repo(), lib.serviceName(), lib.sourceRoots(), lib.indexDdl());
                if (repository.findCatalogLibrary(orgId, lib.id()).isPresent()) {
                    repository.updateCatalogLibrary(orgId, lib.id(), upsert);
                } else {
                    repository.insertCatalogLibrary(orgId, upsert);
                    imported++;
                }
            }
        }
        if (yaml.serviceModules() != null) {
            for (WorkspaceConfig.ServiceModuleConfig module : yaml.serviceModules()) {
                List<String> pinned = module.symbolClasspath() != null
                        ? module.symbolClasspath().stream()
                                .map(WorkspaceConfig.SymbolClasspathEntry::catalogLibrary)
                                .filter(id -> id != null && !id.isBlank())
                                .toList()
                        : List.of();
                var upsert = new OrgWorkspaceConfigRepository.ServiceModuleUpsert(
                        module.id(), module.repo(), module.sourceRoots(), pinned);
                if (repository.findServiceModule(orgId, module.id()).isPresent()) {
                    repository.updateServiceModule(orgId, module.id(), upsert);
                } else {
                    repository.insertServiceModule(orgId, upsert);
                    imported++;
                }
            }
        }
        if (yaml.bundles() != null) {
            for (var entry : yaml.bundles().entrySet()) {
                WorkspaceConfig.BundleConfig bundle = entry.getValue();
                repository.upsertBundle(orgId, new OrgWorkspaceConfigRepository.BundleUpsert(
                        entry.getKey(),
                        bundle.trace() != null ? bundle.trace().shortId() : null,
                        bundle.trace() != null ? bundle.trace().env() : null,
                        bundle.indexOrder()
                ));
                imported++;
            }
        }
        return imported;
    }

    private void validateCatalogLibraryRefs(String orgId, List<String> catalogLibraryIds) {
        if (catalogLibraryIds == null) return;
        for (String libId : catalogLibraryIds) {
            if (libId == null || libId.isBlank()) continue;
            findCatalogLibraryOrThrow(orgId, libId);
        }
    }

    private WorkspaceConfig.CatalogLibraryConfig findCatalogLibraryOrThrow(String orgId, String libraryId) {
        return resolver.resolve(orgId).catalogLibraries().stream()
                .filter(c -> libraryId.equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new CatalogLibraryNotFoundException(orgId, libraryId));
    }

    private WorkspaceConfig.ServiceModuleConfig findServiceModuleOrThrow(String orgId, String moduleId) {
        return resolver.resolve(orgId).serviceModules().stream()
                .filter(m -> moduleId.equals(m.id()))
                .findFirst()
                .orElseThrow(() -> new ServiceModuleNotFoundException(orgId, moduleId));
    }

    private String sourceFor(String orgId) {
        if (repository.hasCatalogLibraries(orgId)
                || repository.hasServiceModules(orgId)
                || repository.hasBundles(orgId)
                || repository.findOrgSettings(orgId).isPresent()) {
            return "database";
        }
        WorkspaceConfig yaml = yamlLoader.getConfig();
        if (orgId.equals(yaml.defaultOrgId())) {
            return "yaml";
        }
        return "empty";
    }

    public record ResolvedWorkspaceView(
            String orgId,
            String githubDir,
            String defaultBundle,
            List<WorkspaceConfig.CatalogLibraryConfig> catalogLibraries,
            List<WorkspaceConfig.ServiceModuleConfig> serviceModules,
            List<String> bundleNames,
            String source
    ) {}

    public record CreateCatalogLibraryRequest(
            @NotBlank String id,
            @NotBlank String repo,
            String serviceName,
            @NotEmpty List<String> sourceRoots,
            Boolean indexDdl
    ) {}

    public record UpdateCatalogLibraryRequest(
            String repo,
            String serviceName,
            List<String> sourceRoots,
            Boolean indexDdl
    ) {}

    public record CreateServiceModuleRequest(
            @NotBlank String id,
            @NotBlank String repo,
            @NotEmpty List<String> sourceRoots,
            List<String> catalogLibraryIds
    ) {}

    public record UpdateServiceModuleRequest(
            String repo,
            List<String> sourceRoots,
            List<String> catalogLibraryIds
    ) {}
}

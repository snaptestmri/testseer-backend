package io.testseer.backend.config.workspace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.registry.ServiceEntry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Tag(name = "Admin — Workspace Catalog",
        description = "Org-scoped catalog library and service module configuration (CFG-CAT)")
@RestController
@RequestMapping("/v1/workspace")
public class WorkspaceCatalogController {

    private final WorkspaceCatalogAdminService adminService;

    public WorkspaceCatalogController(WorkspaceCatalogAdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "Resolved workspace config for an org",
            description = "Merges org DB config with workspace.yml bootstrap for defaultOrgId")
    @GetMapping
    public WorkspaceCatalogAdminService.ResolvedWorkspaceView getWorkspace(
            @Parameter(description = "Organisation ID") @RequestParam String orgId) {
        return adminService.getResolvedConfig(orgId);
    }

    @Operation(summary = "Import workspace.yml into org-scoped DB config")
    @PostMapping("/import-from-yaml")
    public Map<String, Object> importFromYaml(@RequestParam String orgId) {
        int imported = adminService.importFromYaml(orgId);
        return Map.of("orgId", orgId, "importedCount", imported);
    }

    @Operation(summary = "List catalog libraries for an org")
    @GetMapping("/catalog-libraries")
    public List<WorkspaceConfig.CatalogLibraryConfig> listCatalogLibraries(@RequestParam String orgId) {
        return adminService.listCatalogLibraries(orgId);
    }

    @Operation(summary = "Get a catalog library by id")
    @ApiResponse(responseCode = "404", description = "Catalog library not found")
    @GetMapping("/catalog-libraries/{libraryId}")
    public WorkspaceConfig.CatalogLibraryConfig getCatalogLibrary(
            @RequestParam String orgId,
            @PathVariable String libraryId) {
        return adminService.getCatalogLibrary(orgId, libraryId);
    }

    @Operation(summary = "Add a catalog library for an org")
    @ApiResponse(responseCode = "201", description = "Catalog library created")
    @PostMapping("/catalog-libraries")
    public ResponseEntity<WorkspaceConfig.CatalogLibraryConfig> createCatalogLibrary(
            @RequestParam String orgId,
            @Valid @RequestBody WorkspaceCatalogAdminService.CreateCatalogLibraryRequest request) {
        WorkspaceConfig.CatalogLibraryConfig created = adminService.createCatalogLibrary(orgId, request);
        return ResponseEntity.created(URI.create(
                        "/v1/workspace/catalog-libraries/" + created.id() + "?orgId=" + orgId))
                .body(created);
    }

    @Operation(summary = "Update a catalog library")
    @PatchMapping("/catalog-libraries/{libraryId}")
    public WorkspaceConfig.CatalogLibraryConfig updateCatalogLibrary(
            @RequestParam String orgId,
            @PathVariable String libraryId,
            @RequestBody WorkspaceCatalogAdminService.UpdateCatalogLibraryRequest request) {
        return adminService.updateCatalogLibrary(orgId, libraryId, request);
    }

    @Operation(summary = "Delete a catalog library")
    @DeleteMapping("/catalog-libraries/{libraryId}")
    public ResponseEntity<Void> deleteCatalogLibrary(
            @RequestParam String orgId,
            @PathVariable String libraryId) {
        adminService.deleteCatalogLibrary(orgId, libraryId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Register catalog library in service registry",
            description = "Creates moduleType=library registry row matching the org catalog config")
    @PostMapping("/catalog-libraries/{libraryId}/register")
    public ServiceEntry registerCatalogLibrary(
            @RequestParam String orgId,
            @PathVariable String libraryId,
            @RequestParam(defaultValue = "MAVEN") String buildTool) {
        return adminService.registerCatalogLibrary(orgId, libraryId, buildTool);
    }

    @Operation(summary = "List service modules for an org")
    @GetMapping("/service-modules")
    public List<WorkspaceConfig.ServiceModuleConfig> listServiceModules(@RequestParam String orgId) {
        return adminService.listServiceModules(orgId);
    }

    @Operation(summary = "Get a service module by id")
    @GetMapping("/service-modules/{moduleId}")
    public WorkspaceConfig.ServiceModuleConfig getServiceModule(
            @RequestParam String orgId,
            @PathVariable String moduleId) {
        return adminService.getServiceModule(orgId, moduleId);
    }

    @Operation(summary = "Add a service module for an org")
    @PostMapping("/service-modules")
    public ResponseEntity<WorkspaceConfig.ServiceModuleConfig> createServiceModule(
            @RequestParam String orgId,
            @Valid @RequestBody WorkspaceCatalogAdminService.CreateServiceModuleRequest request) {
        WorkspaceConfig.ServiceModuleConfig created = adminService.createServiceModule(orgId, request);
        return ResponseEntity.created(URI.create(
                        "/v1/workspace/service-modules/" + created.id() + "?orgId=" + orgId))
                .body(created);
    }

    @Operation(summary = "Update a service module")
    @PatchMapping("/service-modules/{moduleId}")
    public WorkspaceConfig.ServiceModuleConfig updateServiceModule(
            @RequestParam String orgId,
            @PathVariable String moduleId,
            @RequestBody WorkspaceCatalogAdminService.UpdateServiceModuleRequest request) {
        return adminService.updateServiceModule(orgId, moduleId, request);
    }

    @Operation(summary = "Delete a service module")
    @DeleteMapping("/service-modules/{moduleId}")
    public ResponseEntity<Void> deleteServiceModule(
            @RequestParam String orgId,
            @PathVariable String moduleId) {
        adminService.deleteServiceModule(orgId, moduleId);
        return ResponseEntity.noContent().build();
    }
}

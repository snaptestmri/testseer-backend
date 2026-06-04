package io.testseer.backend.registry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Tag(name = "Service Registry", description = "Register and manage services for TestSeer analysis")
@RestController
@RequestMapping("/registry/services")
public class ServiceRegistryController {

    private final ServiceRegistryService service;

    public ServiceRegistryController(ServiceRegistryService service) {
        this.service = service;
    }

    @Operation(summary = "Register a service",
               description = "Registers a new service for indexing. Returns the assigned serviceId.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Service registered successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid registration request")
    })
    @PostMapping
    public ResponseEntity<ServiceEntry> register(@Valid @RequestBody RegistrationRequest req) {
        ServiceEntry entry = service.register(req);
        return ResponseEntity
                .created(URI.create("/registry/services/" + entry.serviceId()))
                .body(entry);
    }

    @Operation(summary = "List all services",
               description = "Returns all registered services across all organisations.")
    @ApiResponse(responseCode = "200", description = "List of services")
    @GetMapping
    public List<ServiceEntry> listAll() {
        return service.listAll();
    }

    @Operation(summary = "Get a service by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Service found"),
        @ApiResponse(responseCode = "404", description = "Service not found")
    })
    @GetMapping("/{serviceId}")
    public ServiceEntry getById(
            @Parameter(description = "Unique service identifier") @PathVariable String serviceId) {
        return service.getById(serviceId);
    }

    @Operation(summary = "Update service metadata")
    @ApiResponse(responseCode = "200", description = "Service updated")
    @PatchMapping("/{serviceId}")
    public ServiceEntry update(
            @Parameter(description = "Unique service identifier") @PathVariable String serviceId,
            @RequestBody RegistryUpdateRequest req) {
        return service.update(serviceId, req);
    }

    @Operation(summary = "Disable a service",
               description = "Disables a service, stopping future indexing jobs.")
    @ApiResponse(responseCode = "204", description = "Service disabled")
    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> disable(
            @Parameter(description = "Unique service identifier") @PathVariable String serviceId) {
        service.disable(serviceId);
        return ResponseEntity.noContent().build();
    }
}

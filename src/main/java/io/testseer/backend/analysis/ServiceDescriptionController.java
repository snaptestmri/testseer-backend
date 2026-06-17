package io.testseer.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Analysis", description = "Impact analysis and test planning based on the indexed knowledge graph")
@RestController
@RequestMapping("/v1/services")
public class ServiceDescriptionController {

    private final ServiceDescriptionService descriptionService;

    public ServiceDescriptionController(ServiceDescriptionService descriptionService) {
        this.descriptionService = descriptionService;
    }

    @Operation(summary = "Get cached business description for a service")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Description returned"),
        @ApiResponse(responseCode = "404", description = "No description stored yet")
    })
    @GetMapping("/{serviceId}/description")
    public ResponseEntity<ServiceDescriptionResponse> getDescription(@PathVariable String serviceId) {
        return descriptionService.getStoredDetails(serviceId)
                .map(stored -> ResponseEntity.ok(toResponse(serviceId, stored)))
                .orElseThrow(() -> new DescriptionNotFoundException(serviceId));
    }

    private static ServiceDescriptionResponse toResponse(
            String serviceId, ServiceDescriptionService.StoredDescription stored) {
        return new ServiceDescriptionResponse(
                serviceId,
                stored.description(),
                stored.generatedAt(),
                stored.model());
    }
}

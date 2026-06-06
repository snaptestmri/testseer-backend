package io.testseer.backend.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Tag(name = "Analysis", description = "Impact analysis and test planning based on the indexed knowledge graph")
@RestController
@RequestMapping("/v1/services")
public class ServiceDescriptionController {

    private final Optional<ServiceDescriptionService> descriptionService;

    public ServiceDescriptionController(
            Optional<ServiceDescriptionService> descriptionService) {
        this.descriptionService = descriptionService;
    }

    @Operation(summary = "Get cached business description for a service")
    @GetMapping("/{serviceId}/description")
    public ResponseEntity<String> getDescription(@PathVariable String serviceId) {
        if (descriptionService.isEmpty()) {
            return ResponseEntity.status(503)
                    .body("LLM description disabled. Set ANTHROPIC_ENABLED=true.");
        }
        String stored = descriptionService.get().getStored(serviceId);
        if (stored == null) {
            return ResponseEntity.status(404)
                    .body("No description generated yet. POST to this endpoint to generate.");
        }
        return ResponseEntity.ok(stored);
    }

    @Operation(summary = "Generate (or regenerate) business description via Claude API")
    @PostMapping("/{serviceId}/description")
    public ResponseEntity<String> generateDescription(@PathVariable String serviceId) {
        if (descriptionService.isEmpty()) {
            return ResponseEntity.status(503)
                    .body("LLM description disabled. Set ANTHROPIC_ENABLED=true.");
        }
        return ResponseEntity.ok(descriptionService.get().generateAndStore(serviceId));
    }
}

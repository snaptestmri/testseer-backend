package io.testseer.backend.registry;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/registry/services")
public class ServiceRegistryController {

    private final ServiceRegistryService service;

    public ServiceRegistryController(ServiceRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ServiceEntry> register(@Valid @RequestBody RegistrationRequest req) {
        ServiceEntry entry = service.register(req);
        return ResponseEntity
                .created(URI.create("/registry/services/" + entry.serviceId()))
                .body(entry);
    }

    @GetMapping
    public List<ServiceEntry> listAll() {
        return service.listAll();
    }

    @GetMapping("/{serviceId}")
    public ServiceEntry getById(@PathVariable String serviceId) {
        return service.getById(serviceId);
    }

    @PatchMapping("/{serviceId}")
    public ServiceEntry update(@PathVariable String serviceId,
                               @RequestBody RegistryUpdateRequest req) {
        return service.update(serviceId, req);
    }

    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> disable(@PathVariable String serviceId) {
        service.disable(serviceId);
        return ResponseEntity.noContent().build();
    }
}

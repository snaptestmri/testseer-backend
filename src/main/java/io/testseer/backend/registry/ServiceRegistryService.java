package io.testseer.backend.registry;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ServiceRegistryService {

    private final ServiceRegistryRepository repository;

    public ServiceRegistryService(ServiceRegistryRepository repository) {
        this.repository = repository;
    }

    public ServiceEntry register(RegistrationRequest req) {
        repository.findByOrgRepoService(req.orgId(), req.repo(), req.serviceName())
                .ifPresent(existing -> {
                    throw new DuplicateServiceException(req.orgId(), req.repo(), req.serviceName());
                });

        var entry = new ServiceEntry(
                UUID.randomUUID().toString(),
                req.orgId(),
                req.repo(),
                req.serviceName(),
                req.moduleType() != null ? req.moduleType() : "service",
                req.buildTool(),
                req.sourceRoots() != null ? req.sourceRoots() : List.of("src/main/java"),
                req.testRoots()   != null ? req.testRoots()   : List.of("src/test/java"),
                req.ownerTeam(),
                true,
                null,
                null
        );
        repository.save(entry);
        return repository.findById(entry.serviceId()).orElseThrow();
    }

    public ServiceEntry getById(String serviceId) {
        return repository.findById(serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    public List<ServiceEntry> listAll() {
        return repository.findAll();
    }

    public ServiceEntry update(String serviceId, RegistryUpdateRequest req) {
        if (repository.updateFields(serviceId, req) == 0) {
            throw new ServiceNotFoundException(serviceId);
        }
        return repository.findById(serviceId).orElseThrow();
    }

    public void disable(String serviceId) {
        if (repository.disable(serviceId) == 0) {
            throw new ServiceNotFoundException(serviceId);
        }
    }
}

package io.testseer.backend.webhook;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JobDecomposer {

    private final ServiceRegistryService registryService;

    public JobDecomposer(ServiceRegistryService registryService) {
        this.registryService = registryService;
    }

    public List<IngestionJob> decompose(
            String orgId, String repo, String commitSha,
            String jobType, Integer prNumber,
            List<String> changedFiles) {

        List<ServiceEntry> registered = registryService.listAll().stream()
                .filter(s -> s.orgId().equals(orgId) && s.repo().equals(repo) && s.enabled())
                .toList();

        Map<String, List<String>> serviceToFiles = new LinkedHashMap<>();

        for (String file : changedFiles) {
            for (ServiceEntry svc : registered) {
                boolean matches = svc.sourceRoots().stream()
                        .anyMatch(root -> file.startsWith(root + "/") || file.equals(root));
                if (matches) {
                    serviceToFiles.computeIfAbsent(svc.serviceId(), k -> new ArrayList<>()).add(file);
                    break;
                }
            }
        }

        return serviceToFiles.entrySet().stream()
                .map(entry -> new IngestionJob(
                        UUID.randomUUID().toString(),
                        jobType,
                        orgId,
                        repo,
                        entry.getKey(),
                        commitSha,
                        entry.getValue(),
                        prNumber,
                        Instant.now(),
                        1
                ))
                .toList();
    }
}

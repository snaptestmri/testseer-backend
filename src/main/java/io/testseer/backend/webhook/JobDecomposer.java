package io.testseer.backend.webhook;

import io.testseer.backend.config.WorkspaceCatalogService;
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
    private final WorkspaceCatalogService workspaceCatalog;

    public JobDecomposer(
            ServiceRegistryService registryService,
            WorkspaceCatalogService workspaceCatalog) {
        this.registryService = registryService;
        this.workspaceCatalog = workspaceCatalog;
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
            assignFile(orgId, repo, file, registered, serviceToFiles);
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
                        1,
                        null
                ))
                .toList();
    }

    private void assignFile(
            String orgId,
            String repo,
            String file,
            List<ServiceEntry> registered,
            Map<String, List<String>> serviceToFiles) {

        for (ServiceEntry svc : registered) {
            if (matchesAnyRoot(file, effectiveSourceRoots(orgId, svc))) {
                serviceToFiles.computeIfAbsent(svc.serviceId(), k -> new ArrayList<>()).add(file);
                return;
            }
        }
    }

    List<String> effectiveSourceRoots(String orgId, ServiceEntry svc) {
        return workspaceCatalog.resolveRepoProfile(orgId, svc.repo())
                .map(WorkspaceCatalogService.IndexProfile::sourceRoots)
                .filter(roots -> roots != null && !roots.isEmpty())
                .orElse(svc.sourceRoots());
    }

    static boolean matchesAnyRoot(String file, List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return false;
        }
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            if (file.equals(root) || file.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }
}

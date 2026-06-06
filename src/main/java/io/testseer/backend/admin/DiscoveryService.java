package io.testseer.backend.admin;

import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    private final GitHubOrgScanner scanner;
    private final ServiceRegistryService registryService;

    public DiscoveryService(GitHubOrgScanner scanner,
                             ServiceRegistryService registryService) {
        this.scanner        = scanner;
        this.registryService = registryService;
    }

    public DiscoveryResult discover(String orgId) {
        List<GitHubOrgScanner.DetectedRepo> detected = scanner.scanJavaRepos(orgId);

        List<String> registered   = new ArrayList<>();
        List<String> alreadyKnown = new ArrayList<>();
        List<String> skipped      = new ArrayList<>();

        for (GitHubOrgScanner.DetectedRepo repo : detected) {
            try {
                registryService.register(new RegistrationRequest(
                        orgId,
                        repo.name(),
                        repo.name(),
                        repo.buildTool(),
                        "service",
                        List.of("src/main/java"),
                        List.of("src/test/java"),
                        null
                ));
                registered.add(repo.name());
                log.info("Registered {}/{}", orgId, repo.name());
            } catch (DuplicateServiceException ex) {
                alreadyKnown.add(repo.name());
                log.debug("Already registered: {}/{}", orgId, repo.name());
            }
        }

        return new DiscoveryResult(registered, alreadyKnown, skipped);
    }
}
